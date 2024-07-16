// See LICENSE.Berkeley for license details.
// See LICENSE.SiFive for license details.

package freechips.rocketchip.rocket

import chisel3._
import chisel3.util._
import chisel3.withClock
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.tile._
import freechips.rocketchip.util._
import freechips.rocketchip.util.property
import freechips.rocketchip.scie._
//====================== runahead =======================================//
//import freechips.rocketchip.inclusivecache._
//import freechips.rocketchip.subsystem._
// import sifive.blocks.inclusivecache._

//====================== runahead =======================================//
import scala.collection.mutable.ArrayBuffer

case class RocketCoreParams(
  bootFreqHz: BigInt = 0,
  useVM: Boolean = true,
  useUser: Boolean = false,
  useSupervisor: Boolean = false,
  useHypervisor: Boolean = false,
  useDebug: Boolean = true,
  useAtomics: Boolean = true,
  useAtomicsOnlyForIO: Boolean = false,
  useCompressed: Boolean = true,
  useRVE: Boolean = false,
  useSCIE: Boolean = false,
  useBitManip: Boolean = false,
  useBitManipCrypto: Boolean = false,
  useCryptoNIST: Boolean = false,
  useCryptoSM: Boolean = false,
  useConditionalZero: Boolean = false,
  nLocalInterrupts: Int = 0,
  useNMI: Boolean = false,
  nBreakpoints: Int = 1,
  useBPWatch: Boolean = false,
  mcontextWidth: Int = 0,
  scontextWidth: Int = 0,
  nPMPs: Int = 8,
  nPerfCounters: Int = 0,
  haveBasicCounters: Boolean = true,
  haveCFlush: Boolean = false,
  misaWritable: Boolean = true,
  nL2TLBEntries: Int = 0,
  nL2TLBWays: Int = 1,
  nPTECacheEntries: Int = 8,
  mtvecInit: Option[BigInt] = Some(BigInt(0)),
  mtvecWritable: Boolean = true,
  fastLoadWord: Boolean = true,
  fastLoadByte: Boolean = false,
  branchPredictionModeCSR: Boolean = false,
  clockGate: Boolean = false,
  mvendorid: Int = 0, // 0 means non-commercial implementation
  mimpid: Int = 0x20181004, // release date in BCD
  mulDiv: Option[MulDivParams] = Some(MulDivParams()),
  fpu: Option[FPUParams] = Some(FPUParams()),
  haveCease: Boolean = true, // non-standard CEASE instruction
  debugROB: Boolean = false // if enabled, uses a C++ debug ROB to generate trace-with-wdata
) extends CoreParams {
  val lgPauseCycles = 5
  val haveFSDirty = false
  val pmpGranularity: Int = if (useHypervisor) 4096 else 4
  val fetchWidth: Int = if (useCompressed) 2 else 1
  //  fetchWidth doubled, but coreInstBytes halved, for RVC:
  val decodeWidth: Int = fetchWidth / (if (useCompressed) 2 else 1)
  val retireWidth: Int = 1
  val instBits: Int = if (useCompressed) 16 else 32
  val lrscCycles: Int = 80 // worst case is 14 mispredicted branches + slop
  val traceHasWdata: Boolean = false // ooo wb, so no wdata in trace
  override val customIsaExt = Option.when(haveCease)("xrocket") // CEASE instruction
  override def minFLen: Int = fpu.map(_.minFLen).getOrElse(32)
  override def customCSRs(implicit p: Parameters) = new RocketCustomCSRs
}

trait HasRocketCoreParameters extends HasCoreParameters {
  lazy val rocketParams: RocketCoreParams = tileParams.core.asInstanceOf[RocketCoreParams]

  val fastLoadWord = rocketParams.fastLoadWord
  val fastLoadByte = rocketParams.fastLoadByte

  val mulDivParams = rocketParams.mulDiv.getOrElse(MulDivParams()) // TODO ask andrew about this

  val usingABLU = usingBitManip || usingBitManipCrypto
  val aluFn = if (usingABLU) new ABLUFN else new ALUFN

  require(!fastLoadByte || fastLoadWord)
  require(!rocketParams.haveFSDirty, "rocket doesn't support setting fs dirty from outside, please disable haveFSDirty")
  require(!(usingABLU && usingConditionalZero), "Zicond is not yet implemented in ABLU")
}

class RocketCustomCSRs(implicit p: Parameters) extends CustomCSRs with HasRocketCoreParameters {
  override def bpmCSR = {
    rocketParams.branchPredictionModeCSR.option(CustomCSR(bpmCSRId, BigInt(1), Some(BigInt(0))))
  }

  private def haveDCache = tileParams.dcache.get.scratch.isEmpty

  override def chickenCSR = {
    val mask = BigInt(
      tileParams.dcache.get.clockGate.toInt << 0 |
      rocketParams.clockGate.toInt << 1 |
      rocketParams.clockGate.toInt << 2 |
      1 << 3 | // disableSpeculativeICacheRefill
      haveDCache.toInt << 9 | // suppressCorruptOnGrantData
      tileParams.icache.get.prefetch.toInt << 17
    )
    Some(CustomCSR(chickenCSRId, mask, Some(mask)))
  }

  def disableICachePrefetch = getOrElse(chickenCSR, _.value(17), true.B)

  def marchid = CustomCSR.constant(CSRs.marchid, BigInt(1))

  def mvendorid = CustomCSR.constant(CSRs.mvendorid, BigInt(rocketParams.mvendorid))

  // mimpid encodes a release version in the form of a BCD-encoded datestamp.
  def mimpid = CustomCSR.constant(CSRs.mimpid, BigInt(rocketParams.mimpid))

  override def decls = super.decls :+ marchid :+ mvendorid :+ mimpid
}

class Rocket(tile: RocketTile)(implicit p: Parameters) extends CoreModule()(p)
    with HasRocketCoreParameters
    with HasCoreIO {
  def nTotalRoCCCSRs = tile.roccCSRs.flatten.size

  val clock_en_reg = RegInit(true.B)
  val long_latency_stall = Reg(Bool())
  val id_reg_pause = Reg(Bool())
  val imem_might_request_reg = Reg(Bool())
  val clock_en = WireDefault(true.B)
  val gated_clock =
    if (!rocketParams.clockGate) clock
    else ClockGate(clock, clock_en, "rocket_clock_gate")

  class RocketImpl{ // entering gated-clock domain

  // performance counters
  def pipelineIDToWB[T <: Data](x: T): T =
    RegEnable(RegEnable(RegEnable(x, !ctrl_killd), ex_pc_valid), mem_pc_valid)
  val perfEvents = new EventSets(Seq(
    new EventSet((mask, hits) => Mux(wb_xcpt, mask(0), wb_valid && pipelineIDToWB((mask & hits).orR)), Seq(
      ("exception", () => false.B),
      ("load", () => id_ctrl.mem && id_ctrl.mem_cmd === M_XRD && !id_ctrl.fp),
      ("store", () => id_ctrl.mem && id_ctrl.mem_cmd === M_XWR && !id_ctrl.fp),
      ("amo", () => usingAtomics.B && id_ctrl.mem && (isAMO(id_ctrl.mem_cmd) || id_ctrl.mem_cmd.isOneOf(M_XLR, M_XSC))),
      ("system", () => id_ctrl.csr =/= CSR.N),
      ("arith", () => id_ctrl.wxd && !(id_ctrl.jal || id_ctrl.jalr || id_ctrl.mem || id_ctrl.fp || id_ctrl.mul || id_ctrl.div || id_ctrl.csr =/= CSR.N)),
      ("branch", () => id_ctrl.branch),
      ("jal", () => id_ctrl.jal),
      ("jalr", () => id_ctrl.jalr))
      ++ (if (!usingMulDiv) Seq() else Seq(
        ("mul", () => if (pipelinedMul) id_ctrl.mul else id_ctrl.div && (id_ctrl.alu_fn & aluFn.FN_DIV) =/= aluFn.FN_DIV),
        ("div", () => if (pipelinedMul) id_ctrl.div else id_ctrl.div && (id_ctrl.alu_fn & aluFn.FN_DIV) === aluFn.FN_DIV)))
      ++ (if (!usingFPU) Seq() else Seq(
        ("fp load", () => id_ctrl.fp && io.fpu.dec.ldst && io.fpu.dec.wen),
        ("fp store", () => id_ctrl.fp && io.fpu.dec.ldst && !io.fpu.dec.wen),
        ("fp add", () => id_ctrl.fp && io.fpu.dec.fma && io.fpu.dec.swap23),
        ("fp mul", () => id_ctrl.fp && io.fpu.dec.fma && !io.fpu.dec.swap23 && !io.fpu.dec.ren3),
        ("fp mul-add", () => id_ctrl.fp && io.fpu.dec.fma && io.fpu.dec.ren3),
        ("fp div/sqrt", () => id_ctrl.fp && (io.fpu.dec.div || io.fpu.dec.sqrt)),
        ("fp other", () => id_ctrl.fp && !(io.fpu.dec.ldst || io.fpu.dec.fma || io.fpu.dec.div || io.fpu.dec.sqrt))))),
    new EventSet((mask, hits) => (mask & hits).orR, Seq(
      ("load-use interlock", () => id_ex_hazard && ex_ctrl.mem || id_mem_hazard && mem_ctrl.mem || id_wb_hazard && wb_ctrl.mem),
      ("long-latency interlock", () => id_sboard_hazard),
      ("csr interlock", () => id_ex_hazard && ex_ctrl.csr =/= CSR.N || id_mem_hazard && mem_ctrl.csr =/= CSR.N || id_wb_hazard && wb_ctrl.csr =/= CSR.N),
      ("I$ blocked", () => icache_blocked),
      ("D$ blocked", () => id_ctrl.mem && dcache_blocked),
      ("branch misprediction", () => take_pc_mem && mem_direction_misprediction),
      ("control-flow target misprediction", () => take_pc_mem && mem_misprediction && mem_cfi && !mem_direction_misprediction && !icache_blocked),
      ("flush", () => wb_reg_flush_pipe),
      ("replay", () => replay_wb))
      ++ (if (!usingMulDiv) Seq() else Seq(
        ("mul/div interlock", () => id_ex_hazard && (ex_ctrl.mul || ex_ctrl.div) || id_mem_hazard && (mem_ctrl.mul || mem_ctrl.div) || id_wb_hazard && wb_ctrl.div)))
      ++ (if (!usingFPU) Seq() else Seq(
        ("fp interlock", () => id_ex_hazard && ex_ctrl.fp || id_mem_hazard && mem_ctrl.fp || id_wb_hazard && wb_ctrl.fp || id_ctrl.fp && id_stall_fpu)))),
    new EventSet((mask, hits) => (mask & hits).orR, Seq(
      ("I$ miss", () => io.imem.perf.acquire),
      ("D$ miss", () => io.dmem.perf.acquire),
      ("D$ release", () => io.dmem.perf.release),
      ("ITLB miss", () => io.imem.perf.tlbMiss),
      ("DTLB miss", () => io.dmem.perf.tlbMiss),
      ("L2 TLB miss", () => io.ptw.perf.l2miss)))))

  val pipelinedMul = usingMulDiv && mulDivParams.mulUnroll == xLen
  val decode_table = {
    require(!usingRoCC || !rocketParams.useSCIE)
    (if (usingMulDiv) new MDecode(pipelinedMul, aluFn) +: (xLen > 32).option(new M64Decode(pipelinedMul, aluFn)).toSeq else Nil) ++:
    (if (usingAtomics) new ADecode(aluFn) +: (xLen > 32).option(new A64Decode(aluFn)).toSeq else Nil) ++:
    (if (fLen >= 32)    new FDecode(aluFn) +: (xLen > 32).option(new F64Decode(aluFn)).toSeq else Nil) ++:
    (if (fLen >= 64)    new DDecode(aluFn) +: (xLen > 32).option(new D64Decode(aluFn)).toSeq else Nil) ++:
    (if (minFLen == 16) new HDecode(aluFn) +: (xLen > 32).option(new H64Decode(aluFn)).toSeq ++: (fLen >= 64).option(new HDDecode(aluFn)).toSeq else Nil) ++:
    (usingRoCC.option(new RoCCDecode(aluFn))) ++:
    (rocketParams.useSCIE.option(new SCIEDecode(aluFn))) ++:
    (if (usingBitManip) new ZBADecode +: (xLen == 64).option(new ZBA64Decode).toSeq ++: new ZBBMDecode +: new ZBBORCBDecode +: new ZBCRDecode +: new ZBSDecode +: (xLen == 32).option(new ZBS32Decode).toSeq ++: (xLen == 64).option(new ZBS64Decode).toSeq ++: new ZBBSEDecode +: new ZBBCDecode +: (xLen == 64).option(new ZBBC64Decode).toSeq else Nil) ++:
    (if (usingBitManip && !usingBitManipCrypto) (xLen == 32).option(new ZBBZE32Decode).toSeq ++: (xLen == 64).option(new ZBBZE64Decode).toSeq else Nil) ++:
    (if (usingBitManip || usingBitManipCrypto) new ZBBNDecode +: new ZBCDecode +: new ZBBRDecode +: (xLen == 32).option(new ZBBR32Decode).toSeq ++: (xLen == 64).option(new ZBBR64Decode).toSeq ++: (xLen == 32).option(new ZBBREV832Decode).toSeq ++: (xLen == 64).option(new ZBBREV864Decode).toSeq else Nil) ++:
    (if (usingBitManipCrypto) new ZBKXDecode +: new ZBKBDecode +: (xLen == 32).option(new ZBKB32Decode).toSeq ++: (xLen == 64).option(new ZBKB64Decode).toSeq else Nil) ++:
    (if (usingCryptoNIST) (xLen == 32).option(new ZKND32Decode).toSeq ++: (xLen == 64).option(new ZKND64Decode).toSeq else Nil) ++:
    (if (usingCryptoNIST) (xLen == 32).option(new ZKNE32Decode).toSeq ++: (xLen == 64).option(new ZKNE64Decode).toSeq else Nil) ++:
    (if (usingCryptoNIST) new ZKNHDecode +: (xLen == 32).option(new ZKNH32Decode).toSeq ++: (xLen == 64).option(new ZKNH64Decode).toSeq else Nil) ++:
    (usingCryptoSM.option(new ZKSDecode)) ++:
    (if (xLen == 32) new I32Decode(aluFn) else new I64Decode(aluFn)) +:
    (usingVM.option(new SVMDecode(aluFn))) ++:
    (usingSupervisor.option(new SDecode(aluFn))) ++:
    (usingHypervisor.option(new HypervisorDecode(aluFn))) ++:
    ((usingHypervisor && (xLen == 64)).option(new Hypervisor64Decode(aluFn))) ++:
    (usingDebug.option(new DebugDecode(aluFn))) ++:
    (usingNMI.option(new NMIDecode(aluFn))) ++:
    (usingConditionalZero.option(new ConditionalZeroDecode(aluFn))) ++:
    Seq(new FenceIDecode(tile.dcache.flushOnFenceI, aluFn)) ++:
    coreParams.haveCFlush.option(new CFlushDecode(tile.dcache.canSupportCFlushLine, aluFn)) ++:
    rocketParams.haveCease.option(new CeaseDecode(aluFn)) ++:
    Seq(new IDecode(aluFn))
  } flatMap(_.table)

  val ex_ctrl = Reg(new IntCtrlSigs(aluFn))
  val mem_ctrl = Reg(new IntCtrlSigs(aluFn))
  val wb_ctrl = Reg(new IntCtrlSigs(aluFn))

  val ex_reg_xcpt_interrupt  = Reg(Bool())
  val ex_reg_valid           = Reg(Bool())
  val ex_reg_rvc             = Reg(Bool())
  val ex_reg_btb_resp        = Reg(new BTBResp)
  val ex_reg_xcpt            = Reg(Bool())
  val ex_reg_flush_pipe      = Reg(Bool())
  val ex_reg_load_use        = Reg(Bool())
  val ex_reg_cause           = Reg(UInt())
  val ex_reg_replay = Reg(Bool())
  val ex_reg_pc = Reg(UInt())
  val ex_reg_mem_size = Reg(UInt())
  val ex_reg_hls = Reg(Bool())
  val ex_reg_inst = Reg(Bits())
  val ex_reg_raw_inst = Reg(UInt())
  val ex_scie_unpipelined = Reg(Bool())
  val ex_scie_pipelined = Reg(Bool())
  val ex_reg_wphit            = Reg(Vec(nBreakpoints, Bool()))
 //============================================runahead-ex-wxx=============================================//
    val ex_rh_readrf = Reg(Vec(2, Bool()))   //the register we read will be used in ex-stage
    val ex_rh_readfp = Reg(Vec(3, Bool()))
    val ex_rh_raddr = Reg(Vec(3,UInt()))     // the register number
    val ex_fp_wen = Reg(Bool())
    val ex_rh_store = Reg(Bool())
    val ex_rh_load = Reg(Bool())
    dontTouch(ex_rh_readrf)
    dontTouch(ex_rh_raddr)
    dontTouch(ex_rh_readfp)
    dontTouch(ex_fp_wen)
    //============================================runahead=============================================//

  val mem_reg_xcpt_interrupt  = Reg(Bool())
  val mem_reg_valid           = Reg(Bool())
  val mem_reg_rvc             = Reg(Bool())
  val mem_reg_btb_resp        = Reg(new BTBResp)
  val mem_reg_xcpt            = Reg(Bool())
  val mem_reg_replay          = Reg(Bool())
  val mem_reg_flush_pipe      = Reg(Bool())
  val mem_reg_cause           = Reg(UInt())
  val mem_reg_slow_bypass     = Reg(Bool())
  val mem_reg_load            = Reg(Bool())
  val mem_reg_store           = Reg(Bool())
  val mem_reg_sfence = Reg(Bool())
  val mem_reg_pc = Reg(UInt())
  val mem_reg_inst = Reg(Bits())
  val mem_reg_mem_size = Reg(UInt())
  val mem_reg_hls_or_dv = Reg(Bool())
  val mem_reg_raw_inst = Reg(UInt())
  val mem_scie_unpipelined = Reg(Bool())
  val mem_scie_pipelined = Reg(Bool())
  val mem_reg_wdata = Reg(Bits())
  val mem_reg_rs2 = Reg(Bits())
  val mem_br_taken = Reg(Bool())
  val take_pc_mem = Wire(Bool())
  val mem_reg_wphit          = Reg(Vec(nBreakpoints, Bool()))

  val wb_reg_valid           = Reg(Bool())
  val wb_reg_xcpt            = Reg(Bool())
  val wb_reg_replay          = Reg(Bool())
  val wb_reg_flush_pipe      = Reg(Bool())
  val wb_reg_cause           = Reg(UInt())
  val wb_reg_sfence = Reg(Bool())
  val wb_reg_pc = Reg(UInt())
  val wb_reg_mem_size = Reg(UInt())
  val wb_reg_hls_or_dv = Reg(Bool())
  val wb_reg_hfence_v = Reg(Bool())
  val wb_reg_hfence_g = Reg(Bool())
  val wb_reg_inst = Reg(Bits())
  val wb_reg_raw_inst = Reg(UInt())
  val wb_reg_wdata = Reg(Bits())
  val wb_reg_rs2 = Reg(Bits())
  val take_pc_wb = Wire(Bool())
  val wb_reg_wphit           = Reg(Vec(nBreakpoints, Bool()))
  //===================================wxx-runahead======================================//
      //miss-fallingedge-define
  //  val miss_fallingedge = Wire(Bool())
    val l2miss_falingedge = Wire(Bool())
    val db_flag = Wire(Bool())
    val l2miss_counter = RegInit(0.U(32.W))
    val if_l2miss = RegInit(false.B)
  val wb_reg_load            = Reg(Bool())
  val wb_reg_store           = Reg(Bool())
  val wb_rh_store = Reg(Bool())
  //记录读寄存器的地址
  val wb_rh_readrf = Reg(Vec(2, Bool()))
  val wb_rh_readfp = Reg(Vec(3, Bool()))
  val wb_rh_raddr = Reg(Vec(3,UInt()))
  //val wb_fp_wen = Reg(Bool())
  // invalid bit of registerfile
  val rf_invfile = RegInit(0.U(32.W))      //invalid bit file of rf
  val fp_invfile = RegInit(0.U(32.W))      //invalid bit file of fp
  val rf_invfileasbools = VecInit(rf_invfile.asBools)
  val fp_invfileasbools = VecInit(fp_invfile.asBools)
  
  //复制scoreboard
  val rf_sb_wb = RegInit(0.U(32.W))      //scoreboard of rf when runahead beginning
  val fp_sb_wb = RegInit(0.U(32.W))      //scoreboard of fp when runahead beginning
  val rf_sbasbools = VecInit(rf_sb_wb.asBools)
  val fp_sbasbools = VecInit(fp_sb_wb.asBools)
  val wb_mem_rh_addr = Reg(UInt())
  val test_mem_rh_addr = RegInit(VecInit(Seq.fill(5)(0.U(40.W))))       //记录导致l2miss的store地址,和所有rh期间inv的cache addr
  //因为rh期间所有st 都没有执行，所以要把这些地址记录下来，只要后面load了对应地址，就把寄存器inv置为1
  val test_rh_store_addr = RegInit(VecInit(Seq.fill(10)(0.U(40.W))))  
  val rh_RAW = (test_rh_store_addr(0)===wb_mem_rh_addr)|| (test_rh_store_addr(1)===wb_mem_rh_addr)||
               (test_rh_store_addr(2)===wb_mem_rh_addr)|| (test_rh_store_addr(3)===wb_mem_rh_addr)||
               (test_rh_store_addr(4)===wb_mem_rh_addr)|| (test_rh_store_addr(5)===wb_mem_rh_addr)|| 
               (test_rh_store_addr(6)===wb_mem_rh_addr)|| (test_rh_store_addr(7)===wb_mem_rh_addr)||
               (test_rh_store_addr(8)===wb_mem_rh_addr)|| (test_rh_store_addr(9)===wb_mem_rh_addr) && wb_ctrl.mem//&& if_l2miss //&& wb_reg_load
  val runahead_cache_inv = rh_RAW && if_l2miss && wb_ctrl.mem
  val runahead_cache_inv_overflow = RegInit(false.B) 
  //记录rh期间长指令的返回值
  val wb_rh_longinst_stop_record = RegInit(false.B)    //遇到了inv的分支跳转，停止记录
  val rh_rf_waddr_step = RegInit(VecInit(Seq.fill(31)(0.U(10.W))))
  val rh_fp_waddr_step = RegInit(VecInit(Seq.fill(32)(0.U(10.W))))
  val rh_step_wdata = RegInit(VecInit(Seq.fill(20)(0.U(65.W)))) //认为runahead期间最多运行20个长指令（未必有效）
  val rh_step_counter = RegInit(0.U(7.W))    //步长计数器，遇到长指令就+1
  val ex_rh_step_counter = RegInit(0.U(7.W))  //退出rh后的ex阶段长指令步长
  val longinst_wb_flag = RegInit(false.B)  //已经退出这次的rh，还未进入下一次rh，可以写回长指令的寄存值
  val longinst_if_record = RegInit(0.U(20.W))  //每一位代表对应的step是否已记录或写回，1代表已记录，未写回，写回后置0
  val longinst_if_record_sbasbools = VecInit(longinst_if_record.asBools)
  // val runahead_cache_inv =   (test_mem_rh_addr(0)===wb_mem_rh_addr)||
  //                            (test_mem_rh_addr(1)===wb_mem_rh_addr)||
  //                            (test_mem_rh_addr(2)===wb_mem_rh_addr)||
  //                            (test_mem_rh_addr(3)===wb_mem_rh_addr)||
  //                            (test_mem_rh_addr(4)===wb_mem_rh_addr)&& if_l2miss && wb_ctrl.mem //false.B  


  //  val runahead_cache_inv = false.B
  val rh_sourcereg_inv = (wb_rh_readrf(0) && rf_invfile(wb_rh_raddr(0))===1.U) || 
    (wb_rh_readrf(1) && rf_invfile(wb_rh_raddr(1))===1.U) ||
    (wb_rh_readfp(0) && fp_invfile(wb_rh_raddr(0))===1.U) ||
    (wb_rh_readfp(1) && fp_invfile(wb_rh_raddr(1))===1.U) ||
    (wb_rh_readfp(2) && fp_invfile(wb_rh_raddr(2))===1.U) 
  val fp_judgerecord = RegInit(true.B)  //!io.fpu.sboard_set 只能筛掉1cycle，要保证setsboard之后该指令区间内都不能写入longinst_if_record
  val wb_rh_longinst = wb_ctrl.div || wb_ctrl.mul || (wb_ctrl.fp && wb_ctrl.wfd) || wb_reg_load    //表明是一个要记录的长指令，一个指令期间置高1cycle
  val wb_rh_longinst_valid = wb_rh_longinst && !rh_sourcereg_inv && !runahead_cache_inv     //长指令且来源有效，一个指令期间置高1cycle
  val rh_longinst_wrf = longinst_wb_flag && wb_ctrl.wxd && wb_rh_longinst && longinst_if_record(rh_step_counter + 1.U)=== 1.U  //控制写回rf模块的信号
  val rh_longinst_wfp = longinst_wb_flag && wb_ctrl.wfd && wb_rh_longinst && longinst_if_record(rh_step_counter + 1.U)=== 1.U  //控制写回fpu模块的信号
  // 去掉了wb_valid &&
  val ex_rh_longinst = ex_ctrl.div || ex_ctrl.mul || (ex_ctrl.fp && ex_ctrl.wfd) || ex_rh_load 
  val ex_longinst_kill = longinst_wb_flag && ex_rh_longinst && longinst_if_record(ex_rh_step_counter + 1.U)=== 1.U    //需要kill掉ex阶段防止该长指令访存/调用fpu
  //去掉了 && ex_pc_valid
  val fp_sboard = new Scoreboard(32)
   //===================================wxx-runahead======================================//
  
  val take_pc_mem_wb = take_pc_wb || take_pc_mem
  //val take_pc = Reg(Bool())    //runahead take new pc
  val take_pc = take_pc_mem_wb || db_flag || l2miss_falingedge              //runahead take new pc
  dontTouch(take_pc)
  dontTouch(runahead_cache_inv)
  dontTouch(rh_RAW)

  // decode stage
  val ibuf = Module(new IBuf)
  val id_expanded_inst = ibuf.io.inst.map(_.bits.inst)
  val id_raw_inst = ibuf.io.inst.map(_.bits.raw)
  val id_inst = id_expanded_inst.map(_.bits)
  ibuf.io.imem <> io.imem.resp
  ibuf.io.kill := take_pc

  require(decodeWidth == 1 /* TODO */ && retireWidth == decodeWidth)
  require(!(coreParams.useRVE && coreParams.fpu.nonEmpty), "Can't select both RVE and floating-point")
  require(!(coreParams.useRVE && coreParams.useHypervisor), "Can't select both RVE and Hypervisor")
  val id_ctrl = Wire(new IntCtrlSigs(aluFn)).decode(id_inst(0), decode_table)
  val lgNXRegs = if (coreParams.useRVE) 4 else 5
  val regAddrMask = (1 << lgNXRegs) - 1

  def decodeReg(x: UInt) = (x.extract(x.getWidth-1, lgNXRegs).asBool, x(lgNXRegs-1, 0))
  val (id_raddr3_illegal, id_raddr3) = decodeReg(id_expanded_inst(0).rs3)
  val (id_raddr2_illegal, id_raddr2) = decodeReg(id_expanded_inst(0).rs2)
  val (id_raddr1_illegal, id_raddr1) = decodeReg(id_expanded_inst(0).rs1)
  val (id_waddr_illegal,  id_waddr)  = decodeReg(id_expanded_inst(0).rd)

  val id_load_use = Wire(Bool())
  val id_reg_fence = RegInit(false.B)
  val id_ren = IndexedSeq(id_ctrl.rxs1, id_ctrl.rxs2)
  val id_raddr = IndexedSeq(id_raddr1, id_raddr2)
  val rf = new RegFile(regAddrMask, xLen)
  val id_rs = id_raddr.map(rf.read _)
  val ctrl_killd = Wire(Bool())
  val id_npc = (ibuf.io.pc.asSInt + ImmGen(IMM_UJ, id_inst(0))).asUInt  
  dontTouch(id_npc)


  val csr = Module(new CSRFile(perfEvents, coreParams.customCSRs.decls, tile.roccCSRs.flatten))
  val id_csr_en = id_ctrl.csr.isOneOf(CSR.S, CSR.C, CSR.W)
  val id_system_insn = id_ctrl.csr === CSR.I
  val id_csr_ren = id_ctrl.csr.isOneOf(CSR.S, CSR.C) && id_expanded_inst(0).rs1 === 0.U
  val id_csr = Mux(id_system_insn && id_ctrl.mem, CSR.N, Mux(id_csr_ren, CSR.R, id_ctrl.csr))
  val id_csr_flush = id_system_insn || (id_csr_en && !id_csr_ren && csr.io.decode(0).write_flush)

  val id_scie_decoder = if (!rocketParams.useSCIE) WireDefault(0.U.asTypeOf(new SCIEDecoderInterface)) else {
    val d = Module(new SCIEDecoder)
    assert(!io.imem.resp.valid || PopCount(d.io.unpipelined :: d.io.pipelined :: d.io.multicycle :: Nil) <= 1.U)
    d.io.insn := id_raw_inst(0)
    d.io
  }
  val id_illegal_rnum = if (usingCryptoNIST) (id_ctrl.zkn && aluFn.isKs1(id_ctrl.alu_fn) && id_inst(0)(23,20) > 0xA.U(4.W)) else false.B
  val id_illegal_insn = !id_ctrl.legal ||
    (id_ctrl.mul || id_ctrl.div) && !csr.io.status.isa('m'-'a') ||
    id_ctrl.amo && !csr.io.status.isa('a'-'a') ||
    id_ctrl.fp && (csr.io.decode(0).fp_illegal || io.fpu.illegal_rm) ||
    id_ctrl.dp && !csr.io.status.isa('d'-'a') ||
    ibuf.io.inst(0).bits.rvc && !csr.io.status.isa('c'-'a') ||
    id_raddr2_illegal && !id_ctrl.scie && id_ctrl.rxs2 ||
    id_raddr1_illegal && !id_ctrl.scie && id_ctrl.rxs1 ||
    id_waddr_illegal && !id_ctrl.scie && id_ctrl.wxd ||
    id_ctrl.rocc && csr.io.decode(0).rocc_illegal ||
    id_ctrl.scie && !(id_scie_decoder.unpipelined || id_scie_decoder.pipelined) ||
    id_csr_en && (csr.io.decode(0).read_illegal || !id_csr_ren && csr.io.decode(0).write_illegal) ||
    !ibuf.io.inst(0).bits.rvc && (id_system_insn && csr.io.decode(0).system_illegal) ||
    id_illegal_rnum
  val id_virtual_insn = id_ctrl.legal &&
    ((id_csr_en && !(!id_csr_ren && csr.io.decode(0).write_illegal) && csr.io.decode(0).virtual_access_illegal) ||
     (!ibuf.io.inst(0).bits.rvc && id_system_insn && csr.io.decode(0).virtual_system_illegal))
  // stall decode for fences (now, for AMO.rl; later, for AMO.aq and FENCE)
  val id_amo_aq = id_inst(0)(26)
  val id_amo_rl = id_inst(0)(25)
  val id_fence_pred = id_inst(0)(27,24)
  val id_fence_succ = id_inst(0)(23,20)
  val id_fence_next = id_ctrl.fence || id_ctrl.amo && id_amo_aq
  val id_mem_busy = !io.dmem.ordered || io.dmem.req.valid
  when (!id_mem_busy) { id_reg_fence := false.B }
  val id_rocc_busy = usingRoCC.B &&
    (io.rocc.busy || ex_reg_valid && ex_ctrl.rocc ||
     mem_reg_valid && mem_ctrl.rocc || wb_reg_valid && wb_ctrl.rocc)
  val id_do_fence = WireDefault(id_rocc_busy && id_ctrl.fence ||
    id_mem_busy && (id_ctrl.amo && id_amo_rl || id_ctrl.fence_i || id_reg_fence && (id_ctrl.mem || id_ctrl.rocc)))

  val bpu = Module(new BreakpointUnit(nBreakpoints))
  bpu.io.status := csr.io.status
  bpu.io.bp := csr.io.bp
  bpu.io.pc := ibuf.io.pc
  bpu.io.ea := mem_reg_wdata
  bpu.io.mcontext := csr.io.mcontext
  bpu.io.scontext := csr.io.scontext

  val id_xcpt0 = ibuf.io.inst(0).bits.xcpt0
  val id_xcpt1 = ibuf.io.inst(0).bits.xcpt1
  val (id_xcpt, id_cause) = checkExceptions(List(
    (csr.io.interrupt, csr.io.interrupt_cause),
    (bpu.io.debug_if,  CSR.debugTriggerCause.U),
    (bpu.io.xcpt_if,   Causes.breakpoint.U),
    (id_xcpt0.pf.inst, Causes.fetch_page_fault.U),
    (id_xcpt0.gf.inst, Causes.fetch_guest_page_fault.U),
    (id_xcpt0.ae.inst, Causes.fetch_access.U),
    (id_xcpt1.pf.inst, Causes.fetch_page_fault.U),
    (id_xcpt1.gf.inst, Causes.fetch_guest_page_fault.U),
    (id_xcpt1.ae.inst, Causes.fetch_access.U),
    (id_virtual_insn,  Causes.virtual_instruction.U),
    (id_illegal_insn,  Causes.illegal_instruction.U)))

  val idCoverCauses = List(
    (CSR.debugTriggerCause, "DEBUG_TRIGGER"),
    (Causes.breakpoint, "BREAKPOINT"),
    (Causes.fetch_access, "FETCH_ACCESS"),
    (Causes.illegal_instruction, "ILLEGAL_INSTRUCTION")
  ) ++ (if (usingVM) List(
    (Causes.fetch_page_fault, "FETCH_PAGE_FAULT")
  ) else Nil)
  coverExceptions(id_xcpt, id_cause, "DECODE", idCoverCauses)

  val dcache_bypass_data =
    if (fastLoadByte) io.dmem.resp.bits.data(xLen-1, 0)
    else if (fastLoadWord) io.dmem.resp.bits.data_word_bypass(xLen-1, 0)
    else wb_reg_wdata

  // detect bypass opportunities
  val ex_waddr = ex_reg_inst(11,7) & regAddrMask.U
  val mem_waddr = mem_reg_inst(11,7) & regAddrMask.U
  val wb_waddr = wb_reg_inst(11,7) & regAddrMask.U
  val bypass_sources = IndexedSeq(
    (true.B, 0.U, 0.U), // treat reading x0 as a bypass
    (ex_reg_valid && ex_ctrl.wxd, ex_waddr, mem_reg_wdata),
    (mem_reg_valid && mem_ctrl.wxd && !mem_ctrl.mem, mem_waddr, wb_reg_wdata),
    (mem_reg_valid && mem_ctrl.wxd, mem_waddr, dcache_bypass_data))
  val id_bypass_src = id_raddr.map(raddr => bypass_sources.map(s => s._1 && s._2 === raddr))

  // execute stage
  val bypass_mux = bypass_sources.map(_._3)
  val ex_reg_rs_bypass = Reg(Vec(id_raddr.size, Bool()))
  val ex_reg_rs_lsb = Reg(Vec(id_raddr.size, UInt(log2Ceil(bypass_sources.size).W)))
  val ex_reg_rs_msb = Reg(Vec(id_raddr.size, UInt()))
  val ex_rs = for (i <- 0 until id_raddr.size)
    yield Mux(ex_reg_rs_bypass(i), bypass_mux(ex_reg_rs_lsb(i)), Cat(ex_reg_rs_msb(i), ex_reg_rs_lsb(i)))
  val ex_imm = ImmGen(ex_ctrl.sel_imm, ex_reg_inst)
  val ex_op1 = MuxLookup(ex_ctrl.sel_alu1, 0.S, Seq(
    A1_RS1 -> ex_rs(0).asSInt,                    //对应id_raddr1
    A1_PC -> ex_reg_pc.asSInt))
  val ex_op2 = MuxLookup(ex_ctrl.sel_alu2, 0.S, Seq(
    A2_RS2 -> ex_rs(1).asSInt,                 //对应id_raddr2
    A2_IMM -> ex_imm,
    A2_SIZE -> Mux(ex_reg_rvc, 2.S, 4.S)))


  val alu = Module(aluFn match {
    case _: ABLUFN => new ABLU
    case _: ALUFN => new ALU
  })
  alu.io.dw := ex_ctrl.alu_dw
  alu.io.fn := ex_ctrl.alu_fn
  alu.io.in2 := ex_op2.asUInt
  alu.io.in1 := ex_op1.asUInt

  val ex_scie_unpipelined_wdata = if (!rocketParams.useSCIE) 0.U else {
    val u = Module(new SCIEUnpipelined(xLen))
    u.io.insn := ex_reg_inst
    u.io.rs1 := ex_rs(0)
    u.io.rs2 := ex_rs(1)
    u.io.rd
  }

  val mem_scie_pipelined_wdata = if (!rocketParams.useSCIE) 0.U else {
    val u = Module(new SCIEPipelined(xLen))
    u.io.clock := Module.clock
    u.io.valid := ex_reg_valid && ex_scie_pipelined
    u.io.insn := ex_reg_inst
    u.io.rs1 := ex_rs(0)
    u.io.rs2 := ex_rs(1)
    u.io.rd
  }

  val ex_zbk_wdata = if (!usingBitManipCrypto && !usingBitManip) 0.U else {
    val zbk = Module(new BitManipCrypto(xLen))
    zbk.io.fn  := ex_ctrl.alu_fn
    zbk.io.dw  := ex_ctrl.alu_dw
    zbk.io.rs1 := ex_op1.asUInt
    zbk.io.rs2 := ex_op2.asUInt
    zbk.io.rd
  }

  val ex_zkn_wdata = if (!usingCryptoNIST) 0.U else {
    val zkn = Module(new CryptoNIST(xLen))
    zkn.io.fn   := ex_ctrl.alu_fn
    zkn.io.hl   := ex_reg_inst(27)
    zkn.io.bs   := ex_reg_inst(31,30)
    zkn.io.rs1  := ex_op1.asUInt
    zkn.io.rs2  := ex_op2.asUInt
    zkn.io.rd
  }

  val ex_zks_wdata = if (!usingCryptoSM) 0.U else {
    val zks = Module(new CryptoSM(xLen))
    zks.io.fn  := ex_ctrl.alu_fn
    zks.io.bs  := ex_reg_inst(31,30)
    zks.io.rs1 := ex_op1.asUInt
    zks.io.rs2 := ex_op2.asUInt
    zks.io.rd
  }

  // multiplier and divider
  val div = Module(new MulDiv(if (pipelinedMul) mulDivParams.copy(mulUnroll = 0) else mulDivParams, width = xLen, aluFn = aluFn))
  div.io.req.valid := ex_reg_valid && ex_ctrl.div && !ex_longinst_kill   //wxx-runahead防止再次运算长指令调用
  div.io.req.bits.dw := ex_ctrl.alu_dw
  div.io.req.bits.fn := ex_ctrl.alu_fn
  div.io.req.bits.in1 := ex_rs(0)
  div.io.req.bits.in2 := ex_rs(1)
  div.io.req.bits.tag := ex_waddr
  div.io.kill := db_flag                    //wxx-runahead   退出rh时把未完成的乘除kill掉
  val mul = pipelinedMul.option {
    val m = Module(new PipelinedMultiplier(xLen, 2, aluFn = aluFn))
    m.io.req.valid := ex_reg_valid && ex_ctrl.mul && !ex_longinst_kill   //wxx-runahead防止再次运算长指令调用  && !ex_longinst_kill 
    m.io.req.bits := div.io.req.bits
    m
  }

  ex_reg_valid := !ctrl_killd
  ex_reg_replay := !take_pc && ibuf.io.inst(0).valid && ibuf.io.inst(0).bits.replay
  ex_reg_xcpt := !ctrl_killd && id_xcpt
  ex_reg_xcpt_interrupt := !take_pc && ibuf.io.inst(0).valid && csr.io.interrupt

  when (!ctrl_killd) {
    ex_ctrl := id_ctrl
    ex_reg_rvc := ibuf.io.inst(0).bits.rvc
    ex_ctrl.csr := id_csr
    ex_scie_unpipelined := id_ctrl.scie && id_scie_decoder.unpipelined
    ex_scie_pipelined := id_ctrl.scie && id_scie_decoder.pipelined
    when (id_ctrl.fence && id_fence_succ === 0.U) { id_reg_pause := true.B }
    when (id_fence_next) { id_reg_fence := true.B }
    when (id_xcpt) { // pass PC down ALU writeback pipeline for badaddr
      ex_ctrl.alu_fn := aluFn.FN_ADD
      ex_ctrl.alu_dw := DW_XPR
      ex_ctrl.sel_alu1 := A1_RS1 // badaddr := instruction
      ex_ctrl.sel_alu2 := A2_ZERO
      when (id_xcpt1.asUInt.orR) { // badaddr := PC+2
        ex_ctrl.sel_alu1 := A1_PC
        ex_ctrl.sel_alu2 := A2_SIZE
        ex_reg_rvc := true.B
      }
      when (bpu.io.xcpt_if || id_xcpt0.asUInt.orR) { // badaddr := PC
        ex_ctrl.sel_alu1 := A1_PC
        ex_ctrl.sel_alu2 := A2_ZERO
      }
    }
    ex_reg_flush_pipe := id_ctrl.fence_i || id_csr_flush
    ex_reg_load_use := id_load_use
    ex_reg_hls := usingHypervisor.B && id_system_insn && id_ctrl.mem_cmd.isOneOf(M_XRD, M_XWR, M_HLVX)
    ex_reg_mem_size := Mux(usingHypervisor.B && id_system_insn, id_inst(0)(27, 26), id_inst(0)(13, 12))
    when (id_ctrl.mem_cmd.isOneOf(M_SFENCE, M_HFENCEV, M_HFENCEG, M_FLUSH_ALL)) {
      ex_reg_mem_size := Cat(id_raddr2 =/= 0.U, id_raddr1 =/= 0.U)
    }
    when (id_ctrl.mem_cmd === M_SFENCE && csr.io.status.v) {
      ex_ctrl.mem_cmd := M_HFENCEV
    }
    if (tile.dcache.flushOnFenceI) {
      when (id_ctrl.fence_i) {
        ex_reg_mem_size := 0.U
      }
    }

    for (i <- 0 until id_raddr.size) {
      val do_bypass = id_bypass_src(i).reduce(_||_)
      val bypass_src = PriorityEncoder(id_bypass_src(i))
      ex_reg_rs_bypass(i) := do_bypass
      ex_reg_rs_lsb(i) := bypass_src
      when (id_ren(i) && !do_bypass) {
        ex_reg_rs_lsb(i) := id_rs(i)(log2Ceil(bypass_sources.size)-1, 0)
        ex_reg_rs_msb(i) := id_rs(i) >> log2Ceil(bypass_sources.size)
      }
    }
    when (id_illegal_insn || id_virtual_insn) {
      val inst = Mux(ibuf.io.inst(0).bits.rvc, id_raw_inst(0)(15, 0), id_raw_inst(0))
      ex_reg_rs_bypass(0) := false.B
      ex_reg_rs_lsb(0) := inst(log2Ceil(bypass_sources.size)-1, 0)
      ex_reg_rs_msb(0) := inst >> log2Ceil(bypass_sources.size)
    }
  }
  when (!ctrl_killd || csr.io.interrupt || ibuf.io.inst(0).bits.replay) {
    ex_reg_cause := id_cause
    ex_reg_inst := id_inst(0)
    ex_reg_raw_inst := id_raw_inst(0)
    ex_reg_pc := ibuf.io.pc
    ex_reg_btb_resp := ibuf.io.btb_resp
    ex_reg_wphit := bpu.io.bpwatch.map { bpw => bpw.ivalid(0) }
//====================================wxx-runahead=============================//    
    ex_rh_readrf(0) := id_ren(0) && id_raddr1 =/= 0.U
    ex_rh_readrf(1) := id_ren(1) && id_raddr2 =/= 0.U
    ex_rh_readfp(0) := io.fpu.dec.ren1
    ex_rh_readfp(1) := io.fpu.dec.ren2
    ex_rh_readfp(2) := io.fpu.dec.ren3
    ex_rh_raddr(0) := id_raddr1
    ex_rh_raddr(1) := id_raddr2
    ex_rh_raddr(2) := id_raddr3
    ex_fp_wen := io.fpu.dec.wen && io.fpu.valid
    //使rh期间不能写内存（也就是store）
    ex_rh_store := id_ctrl.mem && isWrite(id_ctrl.mem_cmd) && if_l2miss
    ex_rh_load := id_ctrl.mem && isRead(id_ctrl.mem_cmd)
//====================================wxx-runahead=============================//        
  }
  dontTouch(ex_rh_store)
  // replay inst in ex stage?
  val ex_pc_valid = ex_reg_valid || ex_reg_replay || ex_reg_xcpt_interrupt
  val wb_dcache_miss = wb_ctrl.mem && !io.dmem.resp.valid && !wb_rh_store && !rh_longinst_wrf && !rh_longinst_wfp //&& !l2miss_falingedge //wxx-runahead期间不访存, 提前回写记录过的长指令结果时也不会访存
  val replay_ex_structural = ex_ctrl.mem && !io.dmem.req.ready ||
                             ex_ctrl.div && !div.io.req.ready
  val replay_ex_load_use = wb_dcache_miss && ex_reg_load_use
  val replay_ex = ex_reg_replay || (ex_reg_valid && (replay_ex_structural || replay_ex_load_use))
  val ctrl_killx = take_pc_mem_wb || replay_ex || !ex_reg_valid || db_flag || l2miss_falingedge                       //wxx-exit runahead 
  // detect 2-cycle load-use delay for LB/LH/SC
  val ex_slow_bypass = ex_ctrl.mem_cmd === M_XSC || ex_reg_mem_size < 2.U
  val ex_sfence = usingVM.B && ex_ctrl.mem && (ex_ctrl.mem_cmd === M_SFENCE || ex_ctrl.mem_cmd === M_HFENCEV || ex_ctrl.mem_cmd === M_HFENCEG)

  val (ex_xcpt, ex_cause) = checkExceptions(List(
    (ex_reg_xcpt_interrupt || ex_reg_xcpt, ex_reg_cause)))

  val exCoverCauses = idCoverCauses
  coverExceptions(ex_xcpt, ex_cause, "EXECUTE", exCoverCauses)

  // memory stage
  val mem_pc_valid = mem_reg_valid || mem_reg_replay || mem_reg_xcpt_interrupt
  val mem_br_target = mem_reg_pc.asSInt +
    Mux(mem_ctrl.branch && mem_br_taken, ImmGen(IMM_SB, mem_reg_inst),
    Mux(mem_ctrl.jal, ImmGen(IMM_UJ, mem_reg_inst),
    Mux(mem_reg_rvc, 2.S, 4.S)))
  val mem_npc = (Mux(mem_ctrl.jalr || mem_reg_sfence, encodeVirtualAddress(mem_reg_wdata, mem_reg_wdata).asSInt, mem_br_target) & (-2).S).asUInt
  val mem_wrong_npc =
    Mux(ex_pc_valid, mem_npc =/= ex_reg_pc,
    Mux(ibuf.io.inst(0).valid || ibuf.io.imem.valid, mem_npc =/= ibuf.io.pc, true.B))
  val mem_npc_misaligned = !csr.io.status.isa('c'-'a') && mem_npc(1) && !mem_reg_sfence
  val mem_int_wdata = Mux(!mem_reg_xcpt && (mem_ctrl.jalr ^ mem_npc_misaligned), mem_br_target, mem_reg_wdata.asSInt).asUInt
  val mem_cfi = mem_ctrl.branch || mem_ctrl.jalr || mem_ctrl.jal
  val mem_cfi_taken = (mem_ctrl.branch && mem_br_taken) || mem_ctrl.jalr || mem_ctrl.jal
  val mem_direction_misprediction = mem_ctrl.branch && mem_br_taken =/= (usingBTB.B && mem_reg_btb_resp.taken)
  val mem_misprediction = if (usingBTB) mem_wrong_npc else mem_cfi_taken
  take_pc_mem := mem_reg_valid && !mem_reg_xcpt && (mem_misprediction || mem_reg_sfence) 

  mem_reg_valid := !ctrl_killx
  mem_reg_replay := !take_pc_mem_wb && replay_ex
  mem_reg_xcpt := !ctrl_killx && ex_xcpt
  mem_reg_xcpt_interrupt := !take_pc_mem_wb && ex_reg_xcpt_interrupt

  //============================================runahead-mem=============================================//
    //val mem_rh_readrs = Reg(Vec(3, Bool()))
    val mem_rh_raddr = Reg(Vec(3,UInt()))
    val mem_rh_readrf = Reg(Vec(2, Bool()))   //the register we read will be used in ex-stage
    val mem_rh_readfp = Reg(Vec(3, Bool()))
    val mem_fp_wen = Reg(Bool())
    val mem_rh_store = Reg(Bool())
    val mem_rh_addr = Reg(UInt())
    dontTouch(mem_rh_readrf)
    dontTouch(mem_rh_raddr)
    dontTouch(mem_rh_readfp)
 //============================================runahead=============================================//

  // on pipeline flushes, cause mem_npc to hold the sequential npc, which
  // will drive the W-stage npc mux
  when (mem_reg_valid && mem_reg_flush_pipe) {
    mem_reg_sfence := false.B
  }.elsewhen (ex_pc_valid) {
    mem_ctrl := ex_ctrl
    mem_scie_unpipelined := ex_scie_unpipelined
    mem_scie_pipelined := ex_scie_pipelined
    mem_reg_rvc := ex_reg_rvc
    mem_reg_load := ex_ctrl.mem && isRead(ex_ctrl.mem_cmd)
    mem_reg_store := ex_ctrl.mem && isWrite(ex_ctrl.mem_cmd)
    mem_reg_sfence := ex_sfence
    mem_reg_btb_resp := ex_reg_btb_resp
    mem_reg_flush_pipe := ex_reg_flush_pipe
    mem_reg_slow_bypass := ex_slow_bypass
    mem_reg_wphit := ex_reg_wphit

    mem_reg_cause := ex_cause
    mem_reg_inst := ex_reg_inst
    mem_reg_raw_inst := ex_reg_raw_inst
    mem_reg_mem_size := ex_reg_mem_size
    mem_reg_hls_or_dv := io.dmem.req.bits.dv
    mem_reg_pc := ex_reg_pc
  //============================================runahead=============================================//
  for(i <- 0 until 3)
  {
    mem_rh_raddr(i) := ex_rh_raddr(i)
    mem_rh_readfp(i) := ex_rh_readfp(i)
  }
  for(i <- 0 until 2)
  {
    mem_rh_readrf(i) := ex_rh_readrf(i)
  }
  mem_fp_wen := ex_fp_wen
  mem_rh_store := ex_rh_store
  mem_rh_addr := io.dmem.req.bits.addr
  //============================================runahead=============================================//

    // IDecode ensured they are 1H
    mem_reg_wdata := Mux1H(Seq(
      ex_scie_unpipelined -> ex_scie_unpipelined_wdata,
      ex_ctrl.zbk         -> ex_zbk_wdata,
      ex_ctrl.zkn         -> ex_zkn_wdata,
      ex_ctrl.zks         -> ex_zks_wdata,
      (!ex_scie_unpipelined && !ex_ctrl.zbk && !ex_ctrl.zkn && !ex_ctrl.zks)
                          -> alu.io.out,
    ))
    mem_br_taken := alu.io.cmp_out

    when (ex_ctrl.rxs2 && (ex_ctrl.mem || ex_ctrl.rocc || ex_sfence)) {
      val size = Mux(ex_ctrl.rocc, log2Ceil(xLen/8).U, ex_reg_mem_size)
      mem_reg_rs2 := new StoreGen(size, 0.U, ex_rs(1), coreDataBytes).data
    }
    when (ex_ctrl.jalr && csr.io.status.debug) {
      // flush I$ on D-mode JALR to effect uncached fetch without D$ flush
      mem_ctrl.fence_i := true.B
      mem_reg_flush_pipe := true.B
    }
  }

  val mem_breakpoint = (mem_reg_load && bpu.io.xcpt_ld) || (mem_reg_store && bpu.io.xcpt_st)
  val mem_debug_breakpoint = (mem_reg_load && bpu.io.debug_ld) || (mem_reg_store && bpu.io.debug_st)
  val (mem_ldst_xcpt, mem_ldst_cause) = checkExceptions(List(
    (mem_debug_breakpoint, CSR.debugTriggerCause.U),
    (mem_breakpoint,       Causes.breakpoint.U)))

  val (mem_xcpt, mem_cause) = checkExceptions(List(
    (mem_reg_xcpt_interrupt || mem_reg_xcpt, mem_reg_cause),
    (mem_reg_valid && mem_npc_misaligned,    Causes.misaligned_fetch.U),
    (mem_reg_valid && mem_ldst_xcpt,         mem_ldst_cause)))

  val memCoverCauses = (exCoverCauses ++ List(
    (CSR.debugTriggerCause, "DEBUG_TRIGGER"),
    (Causes.breakpoint, "BREAKPOINT"),
    (Causes.misaligned_fetch, "MISALIGNED_FETCH")
  )).distinct
  coverExceptions(mem_xcpt, mem_cause, "MEMORY", memCoverCauses)

  val dcache_kill_mem = mem_reg_valid && mem_ctrl.wxd && io.dmem.replay_next // structural hazard on writeback port
  val fpu_kill_mem = mem_reg_valid && mem_ctrl.fp && io.fpu.nack_mem
  val replay_mem  = dcache_kill_mem || mem_reg_replay || fpu_kill_mem
  val killm_common = dcache_kill_mem || take_pc_wb || mem_reg_xcpt || !mem_reg_valid || db_flag    //wxx-exit runahead 
  div.io.kill := killm_common && RegNext(div.io.req.fire) || db_flag  //               //wxx-exit runahead 
  val ctrl_killm = killm_common || mem_xcpt || fpu_kill_mem  || l2miss_falingedge || db_flag     //wxx-exit runahead 

  // writeback stage
  wb_reg_valid := !ctrl_killm
  wb_reg_replay := replay_mem && !take_pc_wb
  wb_reg_xcpt := mem_xcpt && !take_pc_wb
  wb_reg_flush_pipe := !ctrl_killm && mem_reg_flush_pipe      
  when (mem_pc_valid) {
    wb_ctrl := mem_ctrl
    wb_reg_sfence := mem_reg_sfence
    wb_reg_wdata := Mux(mem_scie_pipelined, mem_scie_pipelined_wdata,
      Mux(!mem_reg_xcpt && mem_ctrl.fp && mem_ctrl.wxd, io.fpu.toint_data, mem_int_wdata))
    when (mem_ctrl.rocc || mem_reg_sfence) {
      wb_reg_rs2 := mem_reg_rs2
    }
    wb_reg_cause := mem_cause
    wb_reg_inst := mem_reg_inst
    wb_reg_raw_inst := mem_reg_raw_inst
    wb_reg_mem_size := mem_reg_mem_size
    wb_reg_hls_or_dv := mem_reg_hls_or_dv
    wb_reg_hfence_v := mem_ctrl.mem_cmd === M_HFENCEV
    wb_reg_hfence_g := mem_ctrl.mem_cmd === M_HFENCEG
    wb_reg_pc := mem_reg_pc
    wb_reg_wphit := mem_reg_wphit | bpu.io.bpwatch.map { bpw => (bpw.rvalid(0) && mem_reg_load) || (bpw.wvalid(0) && mem_reg_store) }
    //==================================wxx-runahead================================//
    for(i <- 0 until 3)
    {
     wb_rh_readfp(i) := mem_rh_readfp(i)
     wb_rh_raddr(i) := mem_rh_raddr(i)
    }
    for(i <- 0 until 2)
    {
     wb_rh_readrf(i) := mem_rh_readrf(i)
    }
    wb_reg_load := mem_reg_load
    wb_reg_store := mem_reg_store
    wb_rh_store := mem_rh_store
  }
    wb_mem_rh_addr := mem_rh_addr
  //==================================wxx-runahead================================//
  val (wb_xcpt, wb_cause) = checkExceptions(List(
    (wb_reg_xcpt,  wb_reg_cause),
    (wb_reg_valid && wb_ctrl.mem && io.dmem.s2_xcpt.pf.st, Causes.store_page_fault.U),
    (wb_reg_valid && wb_ctrl.mem && io.dmem.s2_xcpt.pf.ld, Causes.load_page_fault.U),
    (wb_reg_valid && wb_ctrl.mem && io.dmem.s2_xcpt.gf.st, Causes.store_guest_page_fault.U),
    (wb_reg_valid && wb_ctrl.mem && io.dmem.s2_xcpt.gf.ld, Causes.load_guest_page_fault.U),
    (wb_reg_valid && wb_ctrl.mem && io.dmem.s2_xcpt.ae.st, Causes.store_access.U),
    (wb_reg_valid && wb_ctrl.mem && io.dmem.s2_xcpt.ae.ld, Causes.load_access.U),
    (wb_reg_valid && wb_ctrl.mem && io.dmem.s2_xcpt.ma.st, Causes.misaligned_store.U),
    (wb_reg_valid && wb_ctrl.mem && io.dmem.s2_xcpt.ma.ld, Causes.misaligned_load.U)
  ))

  val wbCoverCauses = List(
    (Causes.misaligned_store, "MISALIGNED_STORE"),
    (Causes.misaligned_load, "MISALIGNED_LOAD"),
    (Causes.store_access, "STORE_ACCESS"),
    (Causes.load_access, "LOAD_ACCESS")
  ) ++ (if(usingVM) List(
    (Causes.store_page_fault, "STORE_PAGE_FAULT"),
    (Causes.load_page_fault, "LOAD_PAGE_FAULT")
  ) else Nil) ++ (if (usingHypervisor) List(
    (Causes.store_guest_page_fault, "STORE_GUEST_PAGE_FAULT"),
    (Causes.load_guest_page_fault, "LOAD_GUEST_PAGE_FAULT"),
  ) else Nil)
  coverExceptions(wb_xcpt, wb_cause, "WRITEBACK", wbCoverCauses)

  val wb_pc_valid = wb_reg_valid || wb_reg_replay || wb_reg_xcpt
  val wb_wxd = wb_reg_valid && wb_ctrl.wxd
  val wb_set_sboard = (wb_ctrl.div || wb_dcache_miss || wb_ctrl.rocc) && !l2miss_falingedge && !rh_longinst_wrf // !rh_longinst_wrf
  val replay_wb_common = io.dmem.s2_nack || wb_reg_replay
  val replay_wb_rocc = wb_reg_valid && wb_ctrl.rocc && !io.rocc.cmd.ready
  val replay_wb = replay_wb_common || replay_wb_rocc
  take_pc_wb := replay_wb || wb_xcpt || csr.io.eret || wb_reg_flush_pipe

  // writeback arbitration
  val dmem_resp_xpu = !io.dmem.resp.bits.tag(0).asBool      //响应是否不是异常（exception）或中断（interrupt）类型,取反后表示不是异常中断类型。
  val dmem_resp_fpu =  io.dmem.resp.bits.tag(0).asBool      //如果tag(0)位为1，表示是浮点单元（FPU）相关的响应
  val dmem_resp_waddr = io.dmem.resp.bits.tag(5, 1)
  val dmem_resp_valid = io.dmem.resp.valid && io.dmem.resp.bits.has_data //&& !rh_longinst_wrf && !rh_longinst_wfp //dmem_resp_valid 信号表示数据存储器响应是否有效且包含数据。
  val dmem_resp_replay = dmem_resp_valid && io.dmem.resp.bits.replay  //信号表示在响应是读取内存之后返回的，而不是直接从dcache中返回的


  div.io.resp.ready := !wb_wxd
  val ll_wdata = WireDefault(div.io.resp.bits.data)
  val ll_waddr = WireDefault(div.io.resp.bits.tag)
  val ll_wen = WireDefault(div.io.resp.fire)
  if (usingRoCC) {
    io.rocc.resp.ready := !wb_wxd
    when (io.rocc.resp.fire) {
      div.io.resp.ready := false.B
      ll_wdata := io.rocc.resp.bits.data
      ll_waddr := io.rocc.resp.bits.rd
      ll_wen := true.B
    }
  }
  when (dmem_resp_replay && dmem_resp_xpu) {
    div.io.resp.ready := false.B
    if (usingRoCC)
      io.rocc.resp.ready := false.B
    ll_waddr := dmem_resp_waddr
    ll_wen := true.B
  }

  val wb_valid = wb_reg_valid && !replay_wb && !wb_xcpt
  val wb_wen = wb_valid && wb_ctrl.wxd     //a. wb_valid表示当前wb阶段的指令提交； b. wb_ctrl.wxd表示当前指令要回写某个寄存器；
  val rf_wen = wb_wen || ll_wen
  val rf_waddr = Mux(ll_wen, ll_waddr, wb_waddr)
  val rf_wdata = Mux(rh_longinst_wrf , rh_step_wdata(rh_step_counter + 1.U),       //wxx-rh长指令提前写回regfile
                 Mux(dmem_resp_valid && dmem_resp_xpu, io.dmem.resp.bits.data(xLen-1, 0),
                 Mux(ll_wen, ll_wdata,
                 Mux(wb_ctrl.csr =/= CSR.N, csr.io.rw.rdata,
                 Mux(wb_ctrl.mul, mul.map(_.io.resp.bits.data).getOrElse(wb_reg_wdata),
                 wb_reg_wdata)))))
 
 //=====================================runahead mode start==========================================================//
  // val fp_judgerecord = RegInit(true.B)  //!io.fpu.sboard_set 只能筛掉1cycle，要保证setsboard之后该指令区间内都不能写入longinst_if_record
  // val wb_rh_longinst = wb_ctrl.div || wb_ctrl.mul || (wb_ctrl.fp && wb_ctrl.wfd) || wb_reg_load    //表明是一个要记录的长指令，一个指令期间置高1cycle
  // val wb_rh_longinst_valid = wb_rh_longinst && !rh_sourcereg_inv && !runahead_cache_inv     //长指令且来源有效，一个指令期间置高1cycle
  // val rh_longinst_wrf = longinst_wb_flag && wb_ctrl.wxd && wb_rh_longinst && longinst_if_record(rh_step_counter + 1.U)=== 1.U  //控制写回rf模块的信号
  // val rh_longinst_wfp = longinst_wb_flag && wb_ctrl.wfd && wb_rh_longinst && longinst_if_record(rh_step_counter + 1.U)=== 1.U  //控制写回fpu模块的信号
  // // 去掉了wb_valid &&
  fp_judgerecord :=  Mux(io.fpu.sboard_set, false.B , 
                     Mux( wb_valid, true.B ,fp_judgerecord ))
  //超过3cycle的延迟就一定会设置sboard
  wb_rh_longinst_stop_record := Mux(l2miss_falingedge||db_flag, false.B ,
                                Mux(!wb_rh_longinst_stop_record, (wb_ctrl.branch||wb_ctrl.jalr) && rh_sourcereg_inv, wb_rh_longinst_stop_record)) 
  //每次进入rh都把wb_rh_longinst_stop_record初始化置低，之后遇到inv分支置高
  rh_step_counter := Mux(l2miss_falingedge && wb_reg_load, 1.U,                                                         //如果导致进入l2miss的指令是load,退出rh之后load会作为长指令第一个被提交，所以rh期间从1开始计数
                     Mux(l2miss_falingedge || db_flag || wb_rh_longinst_stop_record, 0.U,                        //遇到非法分支就停止步长计数   
                     Mux(wb_valid && wb_rh_longinst && rh_step_counter<20.U ,rh_step_counter+1.U,rh_step_counter)))  //提交长指令后计数+1
  //提交长指令（来源未必有效）就步长+1 ，步长最多为100.U（防止退出rh之后步长不停计数）
  dontTouch(rh_step_counter)
  dontTouch(wb_rh_longinst)
  dontTouch(wb_rh_longinst_valid)
  dontTouch(wb_rh_longinst_stop_record )
  dontTouch(rh_rf_waddr_step)
  dontTouch(rh_fp_waddr_step)
  dontTouch(rh_step_wdata)
  dontTouch(rf_wdata)
  val test_counter = RegInit(0.U(2.W))
  val fp_load_valid = RegNext(wb_valid && wb_ctrl.wfd && wb_reg_load && wb_rh_longinst_valid && !io.fpu.sboard_set && !wb_rh_longinst_stop_record) 
  //val fp_load_waddr = Mux(wb_ctrl.wfd && wb_reg_load, wb_waddr, 0.U )
  // runahead期间记录长指令的结果
 when(if_l2miss){     //从rh开始记录when(if_l2miss),或者退出runahead之前一直保持记录when(!longinst_wb_flag)
  when(wb_rh_longinst_valid && !wb_rh_longinst_stop_record && wb_wen)      //说明这是一个写回到rf的长指令，要用到rh_rf_waddr_step 
  {
    rh_rf_waddr_step(wb_waddr):= rh_step_counter + 1.U     //初始值为0
    rh_step_wdata(rh_step_counter + 1.U):= Mux(wb_valid && wb_wen && !wb_set_sboard, rf_wdata, rh_step_wdata(rh_step_counter + 1.U)) //直接写回寄存器的指令
    longinst_if_record_sbasbools(rh_step_counter + 1.U) := true.B
  }.elsewhen(wb_rh_longinst_valid && !wb_rh_longinst_stop_record && wb_ctrl.wfd && wb_valid){ 
    rh_fp_waddr_step(wb_waddr) := rh_step_counter + 1.U
  }
 // when(wb_rh_longinst_valid && !wb_rh_longinst_stop_record && wb_ctrl.wfd && !io.fpu.sboard_set && !wb_reg_load && !io.fpu.fp_ctrl_divsqrt)
  when(wb_rh_longinst_valid && !wb_rh_longinst_stop_record && wb_ctrl.wfd && !io.fpu.fp_fma && !io.fpu.sboard_set && (fp_judgerecord || wb_valid) && !wb_reg_load && !io.fpu.fp_ctrl_divsqrt)
  {
    rh_step_wdata(rh_step_counter + 1.U) := io.fpu.fp_wdata
    longinst_if_record_sbasbools(rh_step_counter + 1.U) := true.B
  }.elsewhen(wb_rh_longinst_valid && !wb_rh_longinst_stop_record && wb_ctrl.wfd && io.fpu.fp_fma && !io.fpu.sboard_set && (fp_judgerecord || wb_valid) && !wb_reg_load && !io.fpu.fp_ctrl_divsqrt){
    test_counter := Mux(wb_valid, 0.U, Mux(io.fpu.fp_fma, test_counter+1.U, 0.U))
    when(test_counter===1.U){
      rh_step_wdata(rh_step_counter) := io.fpu.fp_wdata
      longinst_if_record_sbasbools(rh_step_counter) := true.B}
  }.elsewhen(fp_load_valid){//(wb_rh_longinst_valid && !wb_rh_longinst_stop_record && wb_ctrl.wfd && !io.fpu.sboard_set && wb_reg_load && longinst_if_record(rh_fp_waddr_step(wb_waddr))===0.U) {      
    //  when(rh_fp_waddr_step(wb_waddr)===rh_step_counter) { 
    //   rh_step_wdata(rh_step_counter) := io.fpu.fp_wdata 
    //   longinst_if_record_sbasbools(rh_step_counter) := true.B
    //   }
      // when(longinst_if_record(rh_step_counter)===0.U) { 
      // rh_step_wdata(rh_step_counter) := io.fpu.fp_wdata 
      // longinst_if_record_sbasbools(rh_step_counter) := true.B
      // }
     rh_step_wdata(rh_step_counter) := io.fpu.fp_wdata  
     longinst_if_record_sbasbools(rh_step_counter) := true.B
 }
  //浮点运算的load的wdata会比正常非set_sboard运算的wdata迟1cycle返回， 所以有个when嵌套
  // wb_rh_longinst_valid && !wb_rh_longinst_stop_record && wb_ctrl.wfd -----在该浮点pc的wb阶段内

  when(ll_wen && rf_sb_wb(ll_waddr)===0.U && rh_rf_waddr_step(ll_waddr)=/=0.U){ //&& longinst_if_record(rh_rf_waddr_step(ll_waddr))===0.U){     // set_sboard后写回的指令   
    rh_step_wdata(rh_rf_waddr_step(ll_waddr)) := rf_wdata
    longinst_if_record_sbasbools(rh_rf_waddr_step(ll_waddr)) := true.B
    //longinst_if_record_sbasbools(rh_rf_waddr_step(ll_waddr)) := true.B
  }.elsewhen(io.fpu.sboard_clr && fp_sb_wb(io.fpu.sboard_clra)===0.U && rh_fp_waddr_step(io.fpu.sboard_clra)=/=0.U && longinst_if_record(rh_fp_waddr_step(io.fpu.sboard_clra))===0.U){
    rh_step_wdata(rh_fp_waddr_step(io.fpu.sboard_clra)) := io.fpu.fp_wdata
    longinst_if_record_sbasbools(rh_fp_waddr_step(io.fpu.sboard_clra)) := true.B
  }  
  }
  //有可能是rh之前的sboard写回，所以要rf_sb_wb(ll_waddr)===0.U排除这种可能性

  when(l2miss_falingedge){
  for (i <- 0 until 31) { rh_rf_waddr_step (i) := 0.U }
  for (i <- 0 until 32) { rh_fp_waddr_step (i) := 0.U }
  for (i <- 0 until 20) { rh_step_wdata (i) := 0.U }
  longinst_if_record := 0.U
  }


   // val rcu = Module(new RCU(RCU_Params(xLen)))

//    val origintag = RegInit(0.U(8.W))
//    val runahead_wb_valid = Reg(Bool())
//    io.l1miss := wb_dcache_miss                        //use l1miss instead of l2miss
//    io.fpu.l1miss := io.l1miss                         //use l1miss instead of l2miss
//    when(wb_dcache_miss)                                //l2 signal needs to be corrected
//    {
//      origintag := dmem_resp_waddr
//     // wb_reg_flush_pipe := rcu.io.flush_pipe
//    }
//    when(dmem_resp_valid && (origintag===dmem_resp_waddr))
//    {
//      runahead_wb_valid := true.B
//    }
//    rcu.io.wb_valid:= runahead_wb_valid
//    rcu.io.l2miss := wb_dcache_miss                  //l2 signal needs to be corrected  !io.mshr.directory.hit
//
//      for (i <- 0 until 31) {
//        rcu.io.rf_in(i) := rf.read(i.U)
//      }
//      for (i <- 0 until 32) {
//        rcu.io.fp_in(i) := io.fpu.fp_out(i)
//      }
//      rcu.io.ipc := wb_reg_pc
//    //wb_reg_flush_pipe := rcu.io.flush_pipe
//    when(runahead_wb_valid)
//    {
//      io.imem.req.bits.pc:= rcu.io.opc
//      //wb_reg_flush_pipe := rcu.io.flush_pipe
//      for (j <- 0 until 31) {
//        rf.write (j.U, rcu.io.rf_out(j))
//      }
//      for (j <- 0 until 32) {
//        io.fpu.fp_in(j) := rcu.io.fp_out(j)
//      }
//    }//.elsewhen (rf_wen) { rf.write(rf_waddr, rf_wdata) }
    //====================  runahead  =========================================================//

    //=====================================RAIN_runahead mode==========================================================//
    val rcu = Module(new RCU(RCU_Params(xLen)))

    //trig
    val acquire_r1 = RegNext(io.dmem.perf.acquire,init = false.B)
    val acquire_r2 = RegNext(acquire_r1,init = false.B)
    val l2hit = io.dmem.l2hit
    val prel2miss = RegNext(l2hit,init = false.B)
    l2miss_falingedge := prel2miss && !l2hit && acquire_r2 //10024->1006c,一开始会有很多置高该信号的异常
    //databack
    db_flag := rcu.io.runahead_flag && io.dmem.resp.valid
    //init signal
    rcu.io.l2miss := l2miss_falingedge                
    rcu.io.ipc := wb_reg_pc
    rcu.io.wb_valid := db_flag
    io.fpu.wb_valid := db_flag  //连接fpu模块的wb_valid
    io.fpu.l2miss := l2miss_falingedge   //连接fpu模块的l2miss


    // val insert_stallpc = "h800002F8".U(32.W)   //h80000F7A     00f7e is new l2 miss--wxx-> l2misspc is 482 ->2d0,test for 38a:load can not go
    // //try 292:storemiss   fail end
    // //try 2f8:storemiss 
    // // try 38a:load       counter=48,能跑通，但不太行
    // //try 456:load      counter=59 用时减少11%
    // //try 42a:load      counter=51 3%
    // // val stallpc_flag = (wb_reg_pc === insert_stallpc) && wb_ctrl.mem && (wb_dcache_miss === 1.U)//!io.dmem.resp.valid//(wb_dcache_miss === 1.U) //!io.dmem.resp.valid //
    
    // // addr_range
    // val range_offset_up = 1.U(32.W) // offset
    // val range_offset = 1.U(32.W)
    // val lower_bound = insert_stallpc - range_offset
    // val upper_bound = insert_stallpc + range_offset_up
    // // //miss-fallingedge-define

    // //val prevl1miss = RegNext(wb_dcache_miss, init = false.B)
    // val prevl2miss = RegNext(stallpc_flag, init = false.B)
    // l2miss_falingedge := stallpc_flag && !prevl2miss && wb_ctrl.mem && !io.dmem.resp.valid //&& replay_wb   ////&& wb_valid //&& replay_wb // && wb_valid//
    // rcu.io.l2miss := l2miss_falingedge //stallpc_flag   make l2miss be a pulse signal
    // io.fpu.l2miss := l2miss_falingedge   //连接fpu模块的l2miss
    // //miss_fallingedge := prevl1miss && !wb_dcache_miss
    //================================================test===================================================//
    val if_l2missnext = RegNext(if_l2miss, init = false.B)
    // if_l2miss := Mux(l2miss_falingedge, true.B, Mux((l2miss_counter === 48.U), false.B, if_l2miss))
    if_l2miss := rcu.io.runahead_flag
    l2miss_counter := Mux(if_l2miss, l2miss_counter+1.U, 0.U)
    dontTouch(l2miss_counter)
    dontTouch(if_l2miss)
  
    //================================================test===================================================//
    //db_flag := prevl2miss && (miss_fallingedge === 1.U)

    // rcu.io.wb_valid := db_flag
    // io.fpu.wb_valid := db_flag  //连接fpu模块的wb_valid
    // make checkpoint
    // when(l2miss_falingedge && wb_reg_store){ 
    //   test_mem_rh_addr(0) := wb_mem_rh_addr }.elsewhen(db_flag){ 
    //   for(i <- 0 until 5){
    //   test_mem_rh_addr(i):= 0.U}}.elsewhen(runahead_cache_inv && wb_reg_store && rh_sourcereg_inv ){
    // for(i <- 0 until 5){
    //    if(test_mem_rh_addr(i)==wb_mem_rh_addr){ test_mem_rh_addr(i) := 0.U }} //store来源valid，把该地址删除
    //    }.elsewhen( if_l2miss && wb_reg_store && !rh_sourcereg_inv && !runahead_cache_inv){   //store来源INV，把该地址写入,后续load访问时地址无效，没有prefetch效果
    //    if(test_mem_rh_addr(0)== 0.U){ test_mem_rh_addr(0) := wb_mem_rh_addr } 
    //    else if(test_mem_rh_addr(1)== 0.U){ test_mem_rh_addr(1) := wb_mem_rh_addr } 
    //    else if(test_mem_rh_addr(2)== 0.U){ test_mem_rh_addr(2) := wb_mem_rh_addr } 
    //    else if(test_mem_rh_addr(3)== 0.U){ test_mem_rh_addr(3) := wb_mem_rh_addr } 
    //    else if(test_mem_rh_addr(4)== 0.U){ test_mem_rh_addr(4) := wb_mem_rh_addr } 
    //    else runahead_cache_inv_overflow := true.B
    //    }  // 因为store进入l2miss，记录地址
    //从进入rh开始，只要store就记录地址 模块
    when(l2miss_falingedge && wb_ctrl.mem ){ test_rh_store_addr(0) := wb_mem_rh_addr }.elsewhen(if_l2miss && wb_reg_store && !rh_RAW){   
      test_rh_store_addr(0):= Mux(test_rh_store_addr(0)===0.U,wb_mem_rh_addr,test_rh_store_addr(0))
      for(i <- 1 until 10){
      test_rh_store_addr(i):= Mux(test_rh_store_addr(i)===0.U && test_rh_store_addr(i-1)=/= 0.U, wb_mem_rh_addr ,test_rh_store_addr(i))}

      //  else runahead_cache_inv_overflow := true.B 
      }.elsewhen(db_flag){ 
      for(i <- 0 until 10){
      test_rh_store_addr(i):= 0.U }}
    dontTouch(test_rh_store_addr)
   //从进入rh开始，只要store就记录地址 模块

    for (i <- 0 until 31) {
        rcu.io.rf_in(i) := rf.read(i.U)
      }
    for (i <- 0 until 32) {
        rcu.io.fp_in(i) := io.fpu.fp_out(i)
      }
   //record the pc
    rcu.io.ipc := wb_reg_pc  

    // inv bit of rf        
    when(wb_wen){  
    rf_invfileasbools(wb_waddr) := Mux(l2miss_falingedge , true.B,
    Mux(runahead_cache_inv && wb_reg_load ||  rh_sourcereg_inv    
    ,true.B,false.B))
    }
    rf_invfile:= rf_invfileasbools.asUInt
    //inv bit of fp
    when(wb_ctrl.wfd){
    fp_invfileasbools(wb_waddr) :=  Mux(l2miss_falingedge , true.B,
    Mux(runahead_cache_inv && wb_reg_load || rh_sourcereg_inv     
    ,true.B,false.B))
    }
    fp_invfile:= fp_invfileasbools.asUInt

    
    //==========================wxx-runahead_trick_l2hit=====================================//
    // io.dmem.trick_l2hit := Mux(ibuf.io.pc === rcu.io.opc && if_l2miss, true.B, false.B)    //not-used
    // dontTouch(io.dmem.trick_l2hit)  

    // update the INVregister

    // exit runahead
    when(rcu.io.runahead_backflag){
      for (j <- 0 until 31) {
        rf.write(j.U, rcu.io.rf_out(j))
        }
      for (j <- 0 until 32) {
        io.fpu.fp_in(j) := rcu.io.fp_out(j)
       }

      rf_invfile := 0.U
      fp_invfile := 0.U
    }

    //dontTouch
    // dontTouch(stallpc_flag)
    dontTouch(db_flag)
    dontTouch(l2miss_falingedge)
    dontTouch(wb_rh_readrf)
    dontTouch(wb_rh_readfp)
    dontTouch(wb_rh_raddr)
    dontTouch(rf_invfile)
    //dontTouch(wb_fp_wen)
    
    //====================  RAIN_runahead  =========================================================//

  //when (rf_wen) { rf.write(rf_waddr, rf_wdata) }
      //====================  runahead-begin =========================================================//
  //  when(stallpc_flag && (wb_reg_pc=== rcu.io.opc) && rf_wen){
  //   rf.write(rf_waddr, 100.U)                         //make the target reg a false value then skip the instruction
  //   }.else
  when(rf_wen){ rf.write(rf_waddr, rf_wdata) }

     //====================  runahead-end  =========================================================//
  // hook up control/status regfile
  csr.io.ungated_clock := clock
  csr.io.decode(0).inst := id_inst(0)
  csr.io.exception := wb_xcpt
  csr.io.cause := wb_cause
  csr.io.retire := wb_valid
  csr.io.inst(0) := (if (usingCompressed) Cat(Mux(wb_reg_raw_inst(1, 0).andR, wb_reg_inst >> 16, 0.U), wb_reg_raw_inst(15, 0)) else wb_reg_inst)
  csr.io.interrupts := io.interrupts
  csr.io.hartid := io.hartid
  io.fpu.fcsr_rm := csr.io.fcsr_rm
  csr.io.fcsr_flags := io.fpu.fcsr_flags
  io.fpu.time := csr.io.time(31,0)
  io.fpu.hartid := io.hartid
  csr.io.rocc_interrupt := io.rocc.interrupt
  csr.io.pc := wb_reg_pc
  val tval_dmem_addr = !wb_reg_xcpt
  val tval_any_addr = tval_dmem_addr ||
    wb_reg_cause.isOneOf(Causes.breakpoint.U, Causes.fetch_access.U, Causes.fetch_page_fault.U, Causes.fetch_guest_page_fault.U)
  val tval_inst = wb_reg_cause === Causes.illegal_instruction.U
  val tval_valid = wb_xcpt && (tval_any_addr || tval_inst)
  csr.io.gva := wb_xcpt && (tval_any_addr && csr.io.status.v || tval_dmem_addr && wb_reg_hls_or_dv)
  csr.io.tval := Mux(tval_valid, encodeVirtualAddress(wb_reg_wdata, wb_reg_wdata), 0.U)
  csr.io.htval := {
    val htval_valid_imem = wb_reg_xcpt && wb_reg_cause === Causes.fetch_guest_page_fault.U
    val htval_imem = Mux(htval_valid_imem, io.imem.gpa.bits, 0.U)
    assert(!htval_valid_imem || io.imem.gpa.valid)

    val htval_valid_dmem = wb_xcpt && tval_dmem_addr && io.dmem.s2_xcpt.gf.asUInt.orR && !io.dmem.s2_xcpt.pf.asUInt.orR
    val htval_dmem = Mux(htval_valid_dmem, io.dmem.s2_gpa, 0.U)

    (htval_dmem | htval_imem) >> hypervisorExtraAddrBits
  }
  io.ptw.ptbr := csr.io.ptbr
  io.ptw.hgatp := csr.io.hgatp
  io.ptw.vsatp := csr.io.vsatp
  (io.ptw.customCSRs.csrs zip csr.io.customCSRs).map { case (lhs, rhs) => lhs := rhs }
  io.ptw.status := csr.io.status
  io.ptw.hstatus := csr.io.hstatus
  io.ptw.gstatus := csr.io.gstatus
  io.ptw.pmp := csr.io.pmp
  csr.io.rw.addr := wb_reg_inst(31,20)
  csr.io.rw.cmd := CSR.maskCmd(wb_reg_valid, wb_ctrl.csr)
  csr.io.rw.wdata := wb_reg_wdata
  io.trace.insns := csr.io.trace
  io.trace.time := csr.io.time
  io.rocc.csrs := csr.io.roccCSRs
  if (rocketParams.debugROB) {
    val csr_trace_with_wdata = WireInit(csr.io.trace(0))
    csr_trace_with_wdata.wdata.get := rf_wdata
    DebugROB.pushTrace(clock, reset,
      io.hartid, csr_trace_with_wdata,
      (wb_ctrl.wfd || (wb_ctrl.wxd && wb_waddr =/= 0.U)) && !csr.io.trace(0).exception,
      wb_ctrl.wxd && wb_wen && !wb_set_sboard,
      wb_waddr + Mux(wb_ctrl.wfd, 32.U, 0.U))

    io.trace.insns(0) := DebugROB.popTrace(clock, reset, io.hartid)

    DebugROB.pushWb(clock, reset, io.hartid, ll_wen, rf_waddr, rf_wdata)
  }

  for (((iobpw, wphit), bp) <- io.bpwatch zip wb_reg_wphit zip csr.io.bp) {
    iobpw.valid(0) := wphit
    iobpw.action := bp.control.action
  }

  val hazard_targets = Seq((id_ctrl.rxs1 && id_raddr1 =/= 0.U, id_raddr1),
                           (id_ctrl.rxs2 && id_raddr2 =/= 0.U, id_raddr2),
                           (id_ctrl.wxd  && id_waddr  =/= 0.U, id_waddr))
  val fp_hazard_targets = Seq((io.fpu.dec.ren1, id_raddr1),
                              (io.fpu.dec.ren2, id_raddr2),
                              (io.fpu.dec.ren3, id_raddr3),
                              (io.fpu.dec.wen, id_waddr))

  val sboard = new Scoreboard(32, true)
  sboard.clear(ll_wen, ll_waddr)
  def id_sboard_clear_bypass(r: UInt) = {
    // ll_waddr arrives late when D$ has ECC, so reshuffle the hazard check
    if (!tileParams.dcache.get.dataECC.isDefined) ll_wen && ll_waddr === r
    else div.io.resp.fire && div.io.resp.bits.tag === r || dmem_resp_replay && dmem_resp_xpu && dmem_resp_waddr === r
  }
  val id_sboard_hazard = checkHazards(hazard_targets, rd => sboard.read(rd) && !id_sboard_clear_bypass(rd))     //val rd = wb_waddr
  sboard.set(wb_set_sboard && wb_wen, wb_waddr)
  dontTouch(wb_waddr)

  //  for RAW/WAW hazards on CSRs, loads, AMOs, and mul/div in execute stage.
  val ex_cannot_bypass = ex_ctrl.csr =/= CSR.N || ex_ctrl.jalr || ex_ctrl.mem || ex_ctrl.mul || ex_ctrl.div || ex_ctrl.fp || ex_ctrl.rocc || ex_scie_pipelined
  val data_hazard_ex = ex_ctrl.wxd && checkHazards(hazard_targets, _ === ex_waddr)
  val fp_data_hazard_ex = id_ctrl.fp && ex_ctrl.wfd && checkHazards(fp_hazard_targets, _ === ex_waddr)
  val id_ex_hazard = ex_reg_valid && (data_hazard_ex && ex_cannot_bypass || fp_data_hazard_ex)

  // stall for RAW/WAW hazards on CSRs, LB/LH, and mul/div in memory stage.
  val mem_mem_cmd_bh =
    if (fastLoadWord) (!fastLoadByte).B && mem_reg_slow_bypass
    else true.B
  val mem_cannot_bypass = mem_ctrl.csr =/= CSR.N || mem_ctrl.mem && mem_mem_cmd_bh || mem_ctrl.mul || mem_ctrl.div || mem_ctrl.fp || mem_ctrl.rocc
  val data_hazard_mem = mem_ctrl.wxd && checkHazards(hazard_targets, _ === mem_waddr)
  val fp_data_hazard_mem = id_ctrl.fp && mem_ctrl.wfd && checkHazards(fp_hazard_targets, _ === mem_waddr)
  val id_mem_hazard = mem_reg_valid && (data_hazard_mem && mem_cannot_bypass || fp_data_hazard_mem)
  id_load_use := mem_reg_valid && data_hazard_mem && mem_ctrl.mem

  // stall for RAW/WAW hazards on load/AMO misses and mul/div in writeback.
  val data_hazard_wb = wb_ctrl.wxd && checkHazards(hazard_targets, _ === wb_waddr)
  val fp_data_hazard_wb = id_ctrl.fp && wb_ctrl.wfd && checkHazards(fp_hazard_targets, _ === wb_waddr)
  val id_wb_hazard = wb_reg_valid && (data_hazard_wb && wb_set_sboard || fp_data_hazard_wb)
  //val fp_sboard = new Scoreboard(32)   wxx-runahead
  val id_stall_fpu = if (usingFPU) {
    //val fp_sboard = new Scoreboard(32)
    fp_sboard.set((wb_dcache_miss && wb_ctrl.wfd || io.fpu.sboard_set) && wb_valid, wb_waddr)
    fp_sboard.clear(dmem_resp_replay && dmem_resp_fpu, dmem_resp_waddr)
    fp_sboard.clear(io.fpu.sboard_clr, io.fpu.sboard_clra)
    checkHazards(fp_hazard_targets, fp_sboard.read _)

  } else false.B
 //===================================runahead -wxx-begin========================================================//
     //read the scoreboard, remember the waddr
    when(l2miss_falingedge)
    {
      for(i <- 0 until 31)
      {
        rf_sbasbools(i.U) := sboard.read(i.U)
      }
      for(j <- 0 until 32)
      {
        fp_sbasbools(j.U) := fp_sboard.read(j.U)
      }
    }
    rcu.io.rf_waddr := ll_waddr
    rcu.io.fp_waddr := io.fpu.sboard_clra
    when(if_l2miss && ll_wen && rf_sb_wb(ll_waddr)===1.U)
    {
      rcu.io.rf_sb_valid := true.B     //表明之前未写回的sboard写回了，需要同步
      rf_sbasbools(rf_waddr) := false.B    //完成第一次写回后不需要再更新了
    }.otherwise{rcu.io.rf_sb_valid := false.B}
    when(if_l2miss && io.fpu.sboard_clr && fp_sb_wb(io.fpu.sboard_clra)===1.U)//when(if_l2miss && wb_fp_wen && fp_sb_wb(wb_waddr)===1.U)
    {
      rcu.io.fp_sb_valid := true.B    //表明之前未写回的sboard写回了，需要同步
      fp_sbasbools(wb_waddr) := false.B
    }.otherwise{rcu.io.fp_sb_valid := false.B}
    rf_sb_wb:= rf_sbasbools.asUInt
    fp_sb_wb:= fp_sbasbools.asUInt

    //
      when(rcu.io.runahead_backflag){     //退出rh时，除了进入rh前未写回的sboard位，其他都清空
      for (j <- 0 until 31) {
        sboard.clear(rf_sb_wb(j.U)===0.U, j.U)
        rf_sbasbools(j.U) := false.B
        }
      for (j <- 0 until 32) {
        fp_sboard.clear(fp_sb_wb(j.U)===0.U, j.U)
        fp_sbasbools(j.U) := false.B
       }
    }
  //===================================runahead -wxx-end========================================================//   
  val dcache_blocked = {
    // speculate that a blocked D$ will unblock the cycle after a Grant
    val blocked = Reg(Bool())
    blocked := !io.dmem.req.ready && io.dmem.clock_enabled && !io.dmem.perf.grant && (blocked || io.dmem.req.valid || io.dmem.s2_nack)
    blocked && !io.dmem.perf.grant
  }
  val rocc_blocked = Reg(Bool())
  rocc_blocked := !wb_xcpt && !io.rocc.cmd.ready && (io.rocc.cmd.valid || rocc_blocked)


  val ctrl_stalld =            
    id_ex_hazard || id_mem_hazard || id_wb_hazard || id_sboard_hazard ||
    csr.io.singleStep && (ex_reg_valid || mem_reg_valid || wb_reg_valid) ||
    id_csr_en && csr.io.decode(0).fp_csr && !io.fpu.fcsr_rdy ||
    id_ctrl.fp && id_stall_fpu ||
    //!(ibuf.io.pc === rcu.io.opc && if_l2miss) &&        //wxx-runahead make the pipeline don't stall
    id_ctrl.mem && dcache_blocked || // reduce activity during D$ misses
    id_ctrl.rocc && rocc_blocked || // reduce activity while RoCC is busy
    id_ctrl.div && (!(div.io.req.ready || (div.io.resp.valid && !wb_wxd)) || div.io.req.valid) || // reduce odds of replay
    !clock_en ||
    id_do_fence ||
    csr.io.csr_stall ||
    id_reg_pause ||
    io.traceStall ||
    db_flag
 dontTouch(ctrl_stalld)
 dontTouch(id_ex_hazard)
 dontTouch(id_mem_hazard)
 dontTouch(id_wb_hazard)
 dontTouch(id_sboard_hazard)
 dontTouch(id_ctrl.fp)
 dontTouch(id_ctrl.mem)
 dontTouch(id_ctrl.rocc) 
 dontTouch(id_ctrl.div)   
 dontTouch(id_do_fence) 
 dontTouch(io.traceStall) 
  ctrl_killd := db_flag || l2miss_falingedge ||                    //wxx-exit runahead 
  (!ibuf.io.inst(0).valid || ibuf.io.inst(0).bits.replay || take_pc_mem_wb || ctrl_stalld || csr.io.interrupt)     

  io.imem.req.valid := take_pc
  io.imem.req.bits.speculative := !take_pc_wb
  io.imem.req.bits.pc :=
    Mux(wb_xcpt || csr.io.eret, csr.io.evec, // exception or [m|s]ret
    Mux(l2miss_falingedge && wb_reg_store, mem_reg_pc,           //enter runahead  for 2d0-storemiss
    Mux(l2miss_falingedge && wb_reg_load, ex_reg_pc,           //enter runahead   for 38A-loadmiss
    Mux(db_flag, rcu.io.opc,           //exit runahead
    Mux(replay_wb,              wb_reg_pc,   // replay
                                mem_npc)))))  // flush or branch misprediction
  io.imem.flush_icache := wb_reg_valid && wb_ctrl.fence_i && !io.dmem.s2_nack 
  io.imem.might_request := {
    imem_might_request_reg := ex_pc_valid || mem_pc_valid || io.ptw.customCSRs.disableICacheClockGate || true.B // wxx-runahead 
    imem_might_request_reg
  }
  io.imem.progress := RegNext(wb_reg_valid && !replay_wb_common)
  io.imem.sfence.valid := wb_reg_valid && wb_reg_sfence
  io.imem.sfence.bits.rs1 := wb_reg_mem_size(0)
  io.imem.sfence.bits.rs2 := wb_reg_mem_size(1)
  io.imem.sfence.bits.addr := wb_reg_wdata
  io.imem.sfence.bits.asid := wb_reg_rs2
  io.imem.sfence.bits.hv := wb_reg_hfence_v
  io.imem.sfence.bits.hg := wb_reg_hfence_g
  io.ptw.sfence := io.imem.sfence

  ibuf.io.inst(0).ready := !ctrl_stalld

  io.imem.btb_update.valid := mem_reg_valid && !take_pc_wb && mem_wrong_npc && (!mem_cfi || mem_cfi_taken)
  io.imem.btb_update.bits.isValid := mem_cfi
  io.imem.btb_update.bits.cfiType :=
    Mux((mem_ctrl.jal || mem_ctrl.jalr) && mem_waddr(0), CFIType.call,
    Mux(mem_ctrl.jalr && (mem_reg_inst(19,15) & regAddrMask.U) === BitPat("b00?01"), CFIType.ret,
    Mux(mem_ctrl.jal || mem_ctrl.jalr, CFIType.jump,
    CFIType.branch)))
  io.imem.btb_update.bits.target := io.imem.req.bits.pc
  io.imem.btb_update.bits.br_pc := (if (usingCompressed) mem_reg_pc + Mux(mem_reg_rvc, 0.U, 2.U) else mem_reg_pc)
  io.imem.btb_update.bits.pc := ~(~io.imem.btb_update.bits.br_pc | (coreInstBytes*fetchWidth-1).U)
  io.imem.btb_update.bits.prediction := mem_reg_btb_resp

  io.imem.bht_update.valid := mem_reg_valid && !take_pc_wb
  io.imem.bht_update.bits.pc := io.imem.btb_update.bits.pc
  io.imem.bht_update.bits.taken := mem_br_taken
  io.imem.bht_update.bits.mispredict := mem_wrong_npc
  io.imem.bht_update.bits.branch := mem_ctrl.branch
  io.imem.bht_update.bits.prediction := mem_reg_btb_resp.bht

  io.fpu.valid := !ctrl_killd && id_ctrl.fp
  io.fpu.killx := ctrl_killx
  io.fpu.killm := killm_common //|| db_flag     //wxx-runahead
  io.fpu.inst := id_inst(0)
  io.fpu.fromint_data := ex_rs(0)
  io.fpu.dmem_resp_val := dmem_resp_valid && dmem_resp_fpu && !db_flag //&& !rh_longinst_wfp//!io.fp_longinst_wbflag   //wxx-runahead
  io.fpu.dmem_resp_data := (if (minFLen == 32) io.dmem.resp.bits.data_word_bypass else io.dmem.resp.bits.data)
  io.fpu.dmem_resp_type := io.dmem.resp.bits.size
  io.fpu.dmem_resp_tag := dmem_resp_waddr
  io.fpu.keep_clock_enabled := io.ptw.customCSRs.disableCoreClockGate

  io.dmem.req.valid     := ex_reg_valid && ex_ctrl.mem && !ex_rh_store && !db_flag && !ex_longinst_kill        //wxx-runahead期间不写内存
  val ex_dcache_tag = Cat(ex_waddr, ex_ctrl.fp)
  require(coreParams.dcacheReqTagBits >= ex_dcache_tag.getWidth)
  io.dmem.req.bits.tag  := ex_dcache_tag
  io.dmem.req.bits.cmd  := ex_ctrl.mem_cmd
  io.dmem.req.bits.size := ex_reg_mem_size
  io.dmem.req.bits.signed := !Mux(ex_reg_hls, ex_reg_inst(20), ex_reg_inst(14))
  io.dmem.req.bits.phys := false.B
  io.dmem.req.bits.addr := encodeVirtualAddress(ex_rs(0), alu.io.adder_out)
  io.dmem.req.bits.idx.foreach(_ := io.dmem.req.bits.addr)
  io.dmem.req.bits.dprv := Mux(ex_reg_hls, csr.io.hstatus.spvp, csr.io.status.dprv)
  io.dmem.req.bits.dv := ex_reg_hls || csr.io.status.dv
  io.dmem.s1_data.data := (if (fLen == 0) mem_reg_rs2 else Mux(mem_ctrl.fp, Fill((xLen max fLen) / fLen, io.fpu.store_data), mem_reg_rs2))
  io.dmem.s1_kill := killm_common || mem_ldst_xcpt || fpu_kill_mem //||db_flag   //wxx-runahead  控制改写了kill_common
  io.dmem.s2_kill := false.B || db_flag   //wxx-runahead 
  // don't let D$ go to sleep if we're probably going to use it soon
  io.dmem.keep_clock_enabled := ibuf.io.inst(0).valid && id_ctrl.mem && !csr.io.csr_stall

  io.rocc.cmd.valid := wb_reg_valid && wb_ctrl.rocc && !replay_wb_common
  io.rocc.exception := wb_xcpt && csr.io.status.xs.orR
  io.rocc.cmd.bits.status := csr.io.status
  io.rocc.cmd.bits.inst := wb_reg_inst.asTypeOf(new RoCCInstruction())
  io.rocc.cmd.bits.rs1 := wb_reg_wdata
  io.rocc.cmd.bits.rs2 := wb_reg_rs2
  //=======================runahead-wxx=================================//
    // runahead后写回记录的长指令的值 rf.write要出现在read之后
  
  // val ex_rh_longinst = ex_ctrl.div || ex_ctrl.mul || (ex_ctrl.fp && ex_ctrl.wfd) || ex_rh_load 
  ex_rh_step_counter := Mux(db_flag , 0.U,   //遇到分支预测就停止步长计数   
                        Mux(ex_pc_valid && !mem_reg_replay && ex_rh_longinst && !(take_pc_mem && mem_direction_misprediction) && ex_rh_step_counter<20.U ,ex_rh_step_counter+1.U,ex_rh_step_counter))  //提交长指令后计数+1
  // ex_longinst_kill := longinst_wb_flag && ex_pc_valid && ex_rh_longinst && longinst_if_record(ex_rh_step_counter + 1.U)=== 1.U    //需要kill掉ex阶段防止该长指令访存/调用fpu
  io.fpu.ex_rh_fp_kill := ex_longinst_kill && ex_ctrl.wfd
  dontTouch(ex_rh_step_counter)
  longinst_wb_flag:= Mux(db_flag, true.B, Mux(l2miss_falingedge, false.B, longinst_wb_flag))     //rh结束，长指令结果记录完毕，可以开始写回了
  longinst_if_record := longinst_if_record_sbasbools.asUInt
   io.fpu.fp_longinst_wbflag := rh_longinst_wfp  //控制写回fpu模块的信号
  when(longinst_wb_flag)
  {
    when(wb_valid && wb_rh_longinst && wb_wen && longinst_if_record(rh_step_counter + 1.U)=== 1.U){    //写回整型寄存器：整数乘除，整数load（浮点运算有可能写回整形寄存器吗，应该不会）

        //rf.write(wb_waddr, rh_step_wdata(rh_step_counter + 1.U))
        longinst_if_record_sbasbools(rh_step_counter + 1.U) := false.B
        // when(wb_ctrl.div || wb_ctrl.mul){ 
        //   div.io.kill:= true.B }
        //sboard.clear(wb_set_sboard, wb_waddr)
    }.elsewhen(wb_valid && wb_rh_longinst && wb_ctrl.wfd && longinst_if_record(rh_step_counter + 1.U)=== 1.U){    //写回浮点型 : 除法开方，load，其他运算
        io.fpu.fp_longinst_wdata := rh_step_wdata(rh_step_counter + 1.U)
        io.fpu.fp_longinst_waddr := wb_waddr
        longinst_if_record_sbasbools(rh_step_counter + 1.U) := false.B          
        // when(io.fpu.fp_ctrl_divsqrt){ 
        //   io.fpu.fp_ctrl_divSqrt_killed:= true.B }
    }   
  }
  //=======================runahead-wxx=================================//
  // gate the clock
  val unpause = csr.io.time(rocketParams.lgPauseCycles-1, 0) === 0.U || csr.io.inhibit_cycle || io.dmem.perf.release || take_pc
  when (unpause) { id_reg_pause := false.B }
  io.cease := csr.io.status.cease && !clock_en_reg
  io.wfi := csr.io.status.wfi
  if (rocketParams.clockGate) {
    long_latency_stall := csr.io.csr_stall || io.dmem.perf.blocked || id_reg_pause && !unpause
    clock_en := clock_en_reg || ex_pc_valid || (!long_latency_stall && io.imem.resp.valid)
    clock_en_reg :=
      ex_pc_valid || mem_pc_valid || wb_pc_valid || // instruction in flight
      io.ptw.customCSRs.disableCoreClockGate || // chicken bit
      !div.io.req.ready || // mul/div in flight
      usingFPU.B && !io.fpu.fcsr_rdy || // long-latency FPU in flight
      io.dmem.replay_next || // long-latency load replaying
      (!long_latency_stall && (ibuf.io.inst(0).valid || io.imem.resp.valid)) // instruction pending

    assert(!(ex_pc_valid || mem_pc_valid || wb_pc_valid) || clock_en)
  }

  // evaluate performance counters
  val icache_blocked = !(io.imem.resp.valid || RegNext(io.imem.resp.valid))
  csr.io.counters foreach { c => c.inc := RegNext(perfEvents.evaluate(c.eventSel)) }

  val coreMonitorBundle = Wire(new CoreMonitorBundle(xLen, fLen))

  coreMonitorBundle.clock := clock
  coreMonitorBundle.reset := reset
  coreMonitorBundle.hartid := io.hartid
  coreMonitorBundle.timer := csr.io.time(31,0)
  coreMonitorBundle.valid := csr.io.trace(0).valid && !csr.io.trace(0).exception
  coreMonitorBundle.pc := csr.io.trace(0).iaddr(vaddrBitsExtended-1, 0).sextTo(xLen)
  coreMonitorBundle.wrenx := wb_wen && !wb_set_sboard
  coreMonitorBundle.wrenf := false.B
  coreMonitorBundle.wrdst := wb_waddr
  coreMonitorBundle.wrdata := rf_wdata
  coreMonitorBundle.rd0src := wb_reg_inst(19,15)
  coreMonitorBundle.rd0val := RegNext(RegNext(ex_rs(0)))
  coreMonitorBundle.rd1src := wb_reg_inst(24,20)
  coreMonitorBundle.rd1val := RegNext(RegNext(ex_rs(1)))
  coreMonitorBundle.inst := csr.io.trace(0).insn
  coreMonitorBundle.excpt := csr.io.trace(0).exception
  coreMonitorBundle.priv_mode := csr.io.trace(0).priv

  if (enableCommitLog) {
    val t = csr.io.trace(0)
    val rd = wb_waddr
    val wfd = wb_ctrl.wfd
    val wxd = wb_ctrl.wxd
    val has_data = wb_wen && !wb_set_sboard

    when (t.valid && !t.exception) {
      when (wfd) {
        printf ("%d 0x%x (0x%x) f%d p%d 0xXXXXXXXXXXXXXXXX\n", t.priv, t.iaddr, t.insn, rd, rd+32.U)
      }
      .elsewhen (wxd && rd =/= 0.U && has_data) {
        printf ("%d 0x%x (0x%x) x%d 0x%x\n", t.priv, t.iaddr, t.insn, rd, rf_wdata)
      }
      .elsewhen (wxd && rd =/= 0.U && !has_data) {
        printf ("%d 0x%x (0x%x) x%d p%d 0xXXXXXXXXXXXXXXXX\n", t.priv, t.iaddr, t.insn, rd, rd)
      }
      .otherwise {
        printf ("%d 0x%x (0x%x)\n", t.priv, t.iaddr, t.insn)
      }
    }

    when (ll_wen && rf_waddr =/= 0.U) {
      printf ("x%d p%d 0x%x\n", rf_waddr, rf_waddr, rf_wdata)
    }
  }
  else {
    when (csr.io.trace(0).valid) {
      printf("C%d: %d [%d] pc=[%x] W[r%d=%x][%d] R[r%d=%x] R[r%d=%x] inst=[%x] DASM(%x)\n",
         io.hartid, coreMonitorBundle.timer, coreMonitorBundle.valid,
         coreMonitorBundle.pc,
         Mux(wb_ctrl.wxd || wb_ctrl.wfd, coreMonitorBundle.wrdst, 0.U),
         Mux(coreMonitorBundle.wrenx, coreMonitorBundle.wrdata, 0.U),
         coreMonitorBundle.wrenx,
         Mux(wb_ctrl.rxs1 || wb_ctrl.rfs1, coreMonitorBundle.rd0src, 0.U),
         Mux(wb_ctrl.rxs1 || wb_ctrl.rfs1, coreMonitorBundle.rd0val, 0.U),
         Mux(wb_ctrl.rxs2 || wb_ctrl.rfs2, coreMonitorBundle.rd1src, 0.U),
         Mux(wb_ctrl.rxs2 || wb_ctrl.rfs2, coreMonitorBundle.rd1val, 0.U),
         coreMonitorBundle.inst, coreMonitorBundle.inst)
    }
  }

    // CoreMonitorBundle for late latency writes
  val xrfWriteBundle = Wire(new CoreMonitorBundle(xLen, fLen))

  xrfWriteBundle.clock := clock
  xrfWriteBundle.reset := reset
  xrfWriteBundle.hartid := io.hartid
  xrfWriteBundle.timer := csr.io.time(31,0)
  xrfWriteBundle.valid := false.B
  xrfWriteBundle.pc := 0.U
  xrfWriteBundle.wrdst := rf_waddr
  xrfWriteBundle.wrenx := rf_wen && !(csr.io.trace(0).valid && wb_wen && (wb_waddr === rf_waddr))
  xrfWriteBundle.wrenf := false.B
  xrfWriteBundle.wrdata := rf_wdata
  xrfWriteBundle.rd0src := 0.U
  xrfWriteBundle.rd0val := 0.U
  xrfWriteBundle.rd1src := 0.U
  xrfWriteBundle.rd1val := 0.U
  xrfWriteBundle.inst := 0.U
  xrfWriteBundle.excpt := false.B
  xrfWriteBundle.priv_mode := csr.io.trace(0).priv

  PlusArg.timeout(
    name = "max_core_cycles",
    docstring = "Kill the emulation after INT rdtime cycles. Off if 0."
  )(csr.io.time)

  } // leaving gated-clock domain
  val rocketImpl = withClock (gated_clock) { new RocketImpl }

  def checkExceptions(x: Seq[(Bool, UInt)]) =
    (x.map(_._1).reduce(_||_), PriorityMux(x))

  def coverExceptions(exceptionValid: Bool, cause: UInt, labelPrefix: String, coverCausesLabels: Seq[(Int, String)]): Unit = {
    for ((coverCause, label) <- coverCausesLabels) {
      property.cover(exceptionValid && (cause === coverCause.U), s"${labelPrefix}_${label}")
    }
  }

  def checkHazards(targets: Seq[(Bool, UInt)], cond: UInt => Bool) =
    targets.map(h => h._1 && cond(h._2)).reduce(_||_)

  def encodeVirtualAddress(a0: UInt, ea: UInt) = if (vaddrBitsExtended == vaddrBits) ea else {
    // efficient means to compress 64-bit VA into vaddrBits+1 bits
    // (VA is bad if VA(vaddrBits) != VA(vaddrBits-1))
    val b = vaddrBitsExtended-1
    val a = (a0 >> b).asSInt
    val msb = Mux(a === 0.S || a === -1.S, ea(b), !ea(b-1))
    Cat(msb, ea(b-1, 0))
  }

  class Scoreboard(n: Int, zero: Boolean = false)
  {
    def set(en: Bool, addr: UInt): Unit = update(en, _next | mask(en, addr))
    def clear(en: Bool, addr: UInt): Unit = update(en, _next & ~mask(en, addr))
    def read(addr: UInt): Bool = r(addr)
    def readBypassed(addr: UInt): Bool = _next(addr)

    private val _r = RegInit(0.U(n.W))
    private val r = if (zero) (_r >> 1 << 1) else _r
    private var _next = r
    private var ens = false.B
    private def mask(en: Bool, addr: UInt) = Mux(en, 1.U << addr, 0.U)
    private def update(en: Bool, update: UInt) = {
      _next = update
      ens = ens || en
      when (ens) { _r := _next }
    }
  }
}

class RegFile(n: Int, w: Int, zero: Boolean = false) {
  val rf = Mem(n, UInt(w.W))
  private def access(addr: UInt) = rf(~addr(log2Up(n)-1,0))
  private val reads = ArrayBuffer[(UInt,UInt)]()
  private var canRead = true
  def read(addr: UInt) = {
    require(canRead)
    reads += addr -> Wire(UInt())
    reads.last._2 := Mux(zero.B && addr === 0.U, 0.U, access(addr))
    reads.last._2
  }
  def write(addr: UInt, data: UInt) = {
    canRead = false
    when (addr =/= 0.U) {
      access(addr) := data
      for ((raddr, rdata) <- reads)
        when (addr === raddr) { rdata := data }
    }
  }
}


object ImmGen {
  def apply(sel: UInt, inst: UInt) = {
    val sign = Mux(sel === IMM_Z, 0.S, inst(31).asSInt)
    val b30_20 = Mux(sel === IMM_U, inst(30,20).asSInt, sign)
    val b19_12 = Mux(sel =/= IMM_U && sel =/= IMM_UJ, sign, inst(19,12).asSInt)
    val b11 = Mux(sel === IMM_U || sel === IMM_Z, 0.S,
              Mux(sel === IMM_UJ, inst(20).asSInt,
              Mux(sel === IMM_SB, inst(7).asSInt, sign)))
    val b10_5 = Mux(sel === IMM_U || sel === IMM_Z, 0.U, inst(30,25))
    val b4_1 = Mux(sel === IMM_U, 0.U,
               Mux(sel === IMM_S || sel === IMM_SB, inst(11,8),
               Mux(sel === IMM_Z, inst(19,16), inst(24,21))))
    val b0 = Mux(sel === IMM_S, inst(7),
             Mux(sel === IMM_I, inst(20),
             Mux(sel === IMM_Z, inst(15), 0.U)))

    Cat(sign, b30_20, b19_12, b11, b10_5, b4_1, b0).asSInt
  }
}