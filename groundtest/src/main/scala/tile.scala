package groundtest

import Chisel._
import rocket._
import uncore._
import junctions._
import scala.util.Random
import scala.collection.mutable.ListBuffer
import cde.{Parameters, Field}

case object BuildGroundTest extends Field[(Int, Parameters) => GroundTest]
case object GroundTestMaxXacts extends Field[Int]
case object GroundTestCSRs extends Field[Seq[Int]]
case object TohostAddr extends Field[BigInt]

case object GroundTestCachedClients extends Field[Int]
case object GroundTestUncachedClients extends Field[Int]
case object GroundTestNPTW extends Field[Int]

trait HasGroundTestParameters extends HasAddrMapParameters {
  implicit val p: Parameters
  val nUncached = p(GroundTestUncachedClients)
  val nCached = p(GroundTestCachedClients)
  val nPTW = p(GroundTestNPTW)
  val memStart = addrMap("mem").start
  val memStartBlock = memStart >> p(CacheBlockOffsetBits)
}

/** A "cache" that responds to probe requests with a release indicating
 *  the block is not present */
class DummyCache(implicit val p: Parameters) extends Module {
  val io = new ClientTileLinkIO

  val req = Reg(new Probe)
  val coh = ClientMetadata.onReset
  val (s_probe :: s_release :: Nil) = Enum(Bits(), 2)
  val state = Reg(init = s_probe)

  io.acquire.valid := Bool(false)
  io.probe.ready := (state === s_probe)
  io.grant.ready := Bool(true)
  io.release.valid := (state === s_release)
  io.release.bits := coh.makeRelease(req)
  io.finish.valid := Bool(false)

  when (io.probe.fire()) {
    req := io.probe.bits
    state := s_release
  }

  when (io.release.fire()) {
    state := s_probe
  }
}

class DummyPTW(n: Int)(implicit p: Parameters) extends CoreModule()(p) {
  val io = new Bundle {
    val requestors = Vec(n, new TLBPTWIO).flip
  }

  val req_arb = Module(new RRArbiter(new PTWReq, n))
  req_arb.io.in <> io.requestors.map(_.req)
  req_arb.io.out.ready := Bool(true)

  def vpn_to_ppn(vpn: UInt): UInt = vpn(ppnBits - 1, 0)

  class QueueChannel extends ParameterizedBundle()(p) {
    val ppn = UInt(width = ppnBits)
    val chosen = UInt(width = log2Up(n))
  }

  val s1_ppn = vpn_to_ppn(req_arb.io.out.bits.addr)
  val s2_ppn = RegEnable(s1_ppn, req_arb.io.out.valid)
  val s2_chosen = RegEnable(req_arb.io.chosen, req_arb.io.out.valid)
  val s2_valid = Reg(next = req_arb.io.out.valid)

  val s2_resp = Wire(new PTWResp)
  s2_resp.pte.ppn := s2_ppn
  s2_resp.pte.reserved_for_software := UInt(0)
  s2_resp.pte.d := Bool(true)
  s2_resp.pte.r := Bool(false)
  s2_resp.pte.typ := UInt("b0101")
  s2_resp.pte.v := Bool(true)

  io.requestors.zipWithIndex.foreach { case (requestor, i) =>
    requestor.resp.valid := s2_valid && s2_chosen === UInt(i)
    requestor.resp.bits := s2_resp
    requestor.status.vm := UInt("b01000")
    requestor.status.prv := UInt(PRV.S)
    requestor.invalidate := Bool(false)
  }
}

class GroundTestIO(implicit val p: Parameters) extends ParameterizedBundle()(p)
    with HasGroundTestParameters {
  val cache = Vec(nCached, new HellaCacheIO)
  val mem = Vec(nUncached, new ClientUncachedTileLinkIO)
  val ptw = Vec(nPTW, new TLBPTWIO)
  val finished = Bool(OUTPUT)
}

abstract class GroundTest(implicit val p: Parameters) extends Module
    with HasGroundTestParameters {
  val io = new GroundTestIO
}

class GroundTestTile(id: Int, resetSignal: Bool)
                    (implicit val p: Parameters)
                    extends Tile(resetSignal = resetSignal)(p)
                    with HasGroundTestParameters {

  val test = p(BuildGroundTest)(id, dcacheParams)

  val ptwPorts = ListBuffer.empty ++= test.io.ptw
  val memPorts = ListBuffer.empty ++= test.io.mem

  if (nCached > 0) {
    val dcache = Module(new HellaCache()(dcacheParams))
    val dcacheArb = Module(new HellaCacheArbiter(nCached)(dcacheParams))

    dcacheArb.io.requestor.zip(test.io.cache).foreach {
      case (requestor, cache) =>
        val dcacheIF = Module(new SimpleHellaCacheIF()(dcacheParams))
        dcacheIF.io.requestor <> cache
        requestor <> dcacheIF.io.cache
    }
    dcache.io.cpu <> dcacheArb.io.mem
    io.cached.head <> dcache.io.mem

    // SimpleHellaCacheIF leaves invalidate_lr dangling, so we wire it to false
    dcache.io.cpu.invalidate_lr := Bool(false)

    ptwPorts += dcache.io.ptw
  } else {
    val dcache = Module(new DummyCache)
    io.cached.head <> dcache.io
  }

  // Only Tile 0 needs to write tohost
  if (id == 0) {
    when (test.io.finished) {
      stop()
    }
  }

  if (ptwPorts.size > 0) {
    val ptw = Module(new DummyPTW(ptwPorts.size))
    ptw.io.requestors <> ptwPorts
  }

  require(memPorts.size == io.uncached.size)
  if (memPorts.size > 0) {
    io.uncached <> memPorts
  }
}
