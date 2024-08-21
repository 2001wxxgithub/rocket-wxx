// package freechips.rocketchip.rocket

// import chisel3._
// import chisel3.util.{isPow2,log2Ceil,log2Up,Decoupled,Valid}
// import freechips.rocketchip.util._
// import freechips.rocketchip.diplomacy._
// import freechips.rocketchip.amba._
// import chisel3.experimental.BaseModule


// class dcache_tag  extends Module {
//     val io = IO(new Bundle {
//      val        wen   = Input(UInt(1.W))
//      val        wdata   = Input(UInt(29.W))
//      val        raddr   = Input(UInt(40.W))
//      val        waddr   = Input(UInt(40.W))
//      val        hit   = Output(UInt(1.W))
//      val        valid   = Output(UInt(1.W))
//      val        op      = Input(UInt(1.W))
//      val        tag  =  Output(UInt(28.W))
        
//     })

//     val tag_regs0 = RegInit(VecInit(Seq.fill(64)(0.U(29.W)))) //初始化
//     val tag_regs1 = RegInit(VecInit(Seq.fill(64)(0.U(29.W)))) //初始化寄存器  

//     tag_regs0(io.waddr(11,6)) := Mux((io.op.asBool||io.wen.asBool ) && !io.waddr(5),io.wdata, tag_regs0(io.waddr(11,6)))
//     tag_regs1(io.waddr(11,6)) := Mux((io.op.asBool||io.wen.asBool ) && io.waddr(5),io.wdata, tag_regs1(io.waddr(11,6)))

//     val tag_t0_write = tag_regs0(io.waddr(11,6)) 
//     val tag_t1_write = tag_regs1(io.waddr(11,6))

//     val tag_t0_read = tag_regs0(io.raddr(11,6)) 
//     val tag_t1_read = tag_regs1(io.raddr(11,6))  
//     io.tag := Mux(io.waddr(5),tag_t1_write(19,0),tag_t0_write(19,0))

//     io.valid := Mux(io.raddr(5),tag_t1_read(20),tag_t0_read(20)) //tag_t(20)run
//     io.hit := (io.raddr(5) && tag_t1_read(27,0) === io.raddr(39,12)) || (!io.raddr(5) && tag_t0_read(27,0) === io.raddr(39,12))
// }

