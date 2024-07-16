package freechips.rocketchip.rocket

import chisel3._
import chisel3.experimental.BaseModule
//import freechips.rocketchip.tile._

//import scala.collection.mutable.ArrayBuffer
// import chisel3.stage.ChiselStage


//==========================================================
// Parameters
//==========================================================
case class RCU_Params (
  xLen: Int = 64
)

//==========================================================
// I/Os
//==========================================================
class RCU_IO (params: RCU_Params) extends Bundle {
  //val in = UInt(params.xLen.W)
  //val out = UInt(params.xLen.W)
  val wb_valid = Input(Bool())
  //val tag = Input(UInt)
  val l2miss = Input(Bool())
  val rf_in = Input(Vec(31,UInt(params.xLen.W)))
  val fp_in = Input(Vec(32,UInt(params.xLen.W+1)))     //floating point register file
  val ipc = Input(UInt(40.W))
  val rf_out = Output(Vec(31,UInt(params.xLen.W)))
  val fp_out = Output(Vec(32,UInt(params.xLen.W+1)))    //floating point register file
  //val stall_pipe = Output(Bool())
//  val flush_pipe = Output(Bool())
  val opc = Output(UInt(40.W))
  val runahead_backflag = Output(Bool())
  val runahead_flag = Output(Bool())

  val rf_sb_valid = Input(Bool())
  val rf_waddr = Input(UInt(5.W))
  val fp_sb_valid = Input(Bool())
  val fp_waddr = Input(UInt(5.W))
}


trait Has_RCU_IO extends BaseModule {
  val params: RCU_Params
  val io = IO(new RCU_IO(params))
}

//==========================================================
// Implementations
//==========================================================
class RCU (val params: RCU_Params) extends Module with Has_RCU_IO {
  //val rf_reg = RegInit(0.U(params.xLen.W))
  val rf_reg = RegInit(VecInit(Seq.fill(31)(0.U(params.xLen.W))))
  val fp_reg = RegInit(VecInit(Seq.fill(32)(0.U(params.xLen.W + 1)))) //floating point register file
  val storepc = RegInit(0.U(40.W))
  //initialize
  for (i <- 0 until 31) {
    io.rf_out(i) := 0.U(params.xLen.W)
  }
  for (i <- 0 until 32) {
    io.fp_out(i) := 0.U(params.xLen.W + 1)
  }
  io.runahead_backflag := false.B
  io.runahead_flag := false.B
  val rh_flag = RegInit(0.U(1.W))
  io.runahead_flag := rh_flag
  io.runahead_flag := rh_flag
  dontTouch(io)
  dontTouch(rf_reg)

  when(io.l2miss) {
    for (i <- 0 until 31) {
      rh_flag := 1.U
      rf_reg(i) := io.rf_in(i)
    }
    for (i <- 0 until 32) {
      fp_reg(i) := io.fp_in(i)
    }
    storepc:= io.ipc
  }
  io.opc := storepc
  //if the sboard addr's value come back
  when(io.rf_sb_valid){rf_reg(io.rf_waddr) := io.rf_in(io.rf_waddr)}
  when(io.fp_sb_valid){fp_reg(io.fp_waddr) := io.fp_in(io.fp_waddr)}

  when(io.wb_valid) {
    io.runahead_backflag := true.B
    rh_flag := 0.U
    for (j <- 0 until 31) {
      io.rf_out(j) := rf_reg(j)
      //rf_reg(j) := 0.U(params.xLen.W)
    }
    for (j <- 0 until 32) {
      io.fp_out(j) := fp_reg(j)
      //fp_reg(j) := 0.U(params.xLen.W + 1)
    }
  }

  /* For test only */
  // val l2miss_counter = RegInit(0.U(4.W))
  // val if_l2miss = RegInit(0.U(1.W))

  // if_l2miss := Mux(io.l2miss, 1.U, Mux((l2miss_counter === 4.U), 0.U, if_l2miss))
  // l2miss_counter := Mux(if_l2miss.asBool, l2miss_counter+1.U, 0.U)
  // io.runahead_zeroflag := Mux(l2miss_counter === 4.U, 1.U, 0.U)
}
