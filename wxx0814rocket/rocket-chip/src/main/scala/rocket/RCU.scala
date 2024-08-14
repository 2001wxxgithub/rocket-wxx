//==========================================================
// RAIN_RCU_runahead
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
  val wb_valid = Input(Bool())
  val l2miss = Input(Bool())
  val rf_in = Input(Vec(31,UInt(params.xLen.W)))
  //val fp_in = Input(Vec(32,UInt(params.xLen.W+1)))     //floating point register file
  val sb_in = Input(Vec(31,UInt(1.W)))
  
  val ipc = Input(UInt(40.W))
  val rf_out = Output(Vec(31,UInt(params.xLen.W)))
  //val fp_out = Output(Vec(32,UInt(params.xLen.W+1)))    //floating point register file

  val sb_out = Output(Vec(31,UInt(1.W)))
//  val runahead_backflag = Output(Bool())
//  val runahead_trig = Output(Bool())
  val runahead_flag = Output(Bool())
  val opc = Output(UInt(40.W))
//checkpoint sb
  val rf_sb_valid = Input(Bool())
  val rf_waddr = Input(UInt(5.W))
  // val fp_sb_valid = Input(Bool())
  // val fp_waddr = Input(UInt(5.W))

  // val fp_out = Output(Vec(32,UInt(params.xLen.W+1)))    //floating point register file
  // val stall_pipe = Output(Bool())
  // val opc = Output(UInt(40.W))
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
  //val fp_reg = RegInit(VecInit(Seq.fill(32)(0.U(params.xLen.W + 1)))) //floating point register file

  val sb_reg = RegInit(VecInit(Seq.fill(31)(0.U(1.W))))
  //val fp_sb_reg = RegInit(VecInit(Seq.fill(32)(0.U(1.W))))
  val storepc = RegInit(0.U(40.W))

  val rh_flag = RegInit(false.B)
  io.runahead_flag := rh_flag
//  dontTouch(rh_flag)
  dontTouch(io)
  dontTouch(rf_reg)
  dontTouch(sb_reg)

  //==========================================================method 1
//checkpoint
  when(io.l2miss) {
    rh_flag := true.B
    for (i <- 0 until 31) {
      //io.runahead_trig := true.B
      rf_reg(i) := io.rf_in(i)
      sb_reg(i) := io.sb_in(i)
    }
    // for (i <- 0 until 32) {
    //   fp_reg(i) := io.fp_in(i)
    // }
    storepc := io.ipc
  }
  io.opc := storepc

//update the scoreboard
  when(io.rf_sb_valid){rf_reg(io.rf_waddr) := io.rf_in(io.rf_waddr)}
  //when(io.fp_sb_valid){fp_reg(io.fp_waddr) := io.fp_in(io.fp_waddr)}

//write back
    for (j <- 0 until 31) {
      io.rf_out(j) := rf_reg(j)
      io.sb_out(j) := sb_reg(j)
      //rf_reg(j) := 0.U(params.xLen.W)
    }
    // for (j <- 0 until 32) {
    //   io.fp_out(j) := fp_reg(j)
    //   //fp_reg(j) := 0.U(params.xLen.W + 1)
    // }
    when(io.wb_valid) {
      rh_flag := false.B
    }



  /* for(j<-0 until 31)
  {
    io.rf_out(j) := Mux(counter===500.U, rf_reg(j), 0.U)
  }*/
  //io.rf_out := Mux(counter === 500.U, rf_reg1, 0.U)
  //==========================================================method 1
  
}
//==========================================================