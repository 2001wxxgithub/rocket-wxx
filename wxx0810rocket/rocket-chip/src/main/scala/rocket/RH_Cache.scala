// //r_runahead cache
// //==================r_runahead strat===================================

// package freechips.rocketchip.rocket

// import chisel3._
// import chisel3.util.{isPow2,log2Ceil,log2Up,Decoupled,Valid}
// import freechips.rocketchip.util._
// import freechips.rocketchip.diplomacy._
// import freechips.rocketchip.amba._
// import chisel3.experimental.BaseModule

// class rhcache (depth: Int) extends Module {//64 depth?
//   val io = IO(new Bundle {
//     //cpu -> rhcache
//     //input
//     val cpu_req = Input (UInt(1.W)) //cpu(send)->cache
//     val cpu_addr= Input (UInt(40.W))
//     val cpu_wr_ctrl = Input(UInt(1.W))//en 1->w 0->r
//     val cpu_wbyte = Input(UInt(8.W)) //mask
//     val cpu_wdata = Input(UInt(64.W))
//     val inv_flush = Input(UInt(1.W))

//     //output
//     val cpu_rdata = Output(UInt(64.W))
//     val cpu_resp = Output(UInt(1.W))

//     //cache -> dcache
//     val ram_req = Output(UInt(1.W))
//     val ram_wr_ctrl = Output(UInt(1.W))
//     val ram_wbyte = Output(UInt(8.W))
//     val ram_addr = Output(UInt(40.W))
//     val ram_wdata = Output(UInt(64.W))
//     val uncached = Output(UInt(1.W))

//     val ram_rdata = Input(UInt(64.W))
//     val ram_beat_done = Input(UInt(1.W))
//     val ram_resp = Input(UInt(1.W))
//   }
//   )

//   //extract tag/index/offset
//   val tag = io.cpu_addr(39,12)
//   val index = io.cpu_addr(11,6)
//   val offset = io.cpu_addr(5,0)

//   //define v_array/tag_array/cache_line
//   val V_array = RegInit(VecInit(Seq.fill(depth)(0.U(1.W))))
//   val Tag_array = RegInit(VecInit(Seq.fill(depth)(0.U(28.W))))
//   val data_array = RegInit(VecInit(Seq.fill(depth)(0.U(128.W))))

//   //delay 1 cycle for req
//   val req = RegInit(0.U(1.W))
//   req := MuxCase(req,Seq(
//     (io.cpu_resp === 1.U) -> 0.U,
//     (io.cpu.req === 1.U && req === 0.U) -> 1.U
//   ))

//   //hit?
//   val hit = Mux((Tag_array(index) === tag && V_array(index) === 1.U) && io.uncached === 0.U,1.U,0.U)

//   //if hit,send back data(load op)
//   io.cpu_rdata := MuxCase(0.U,Seq(
//     (io.cpu_wr_ctrl === 0.U && hit === 1.U && io.uncached === 0.U) ->
//       Mux(offset(5) === 1.U,data_array(index)(63,0),data_array(index)(127,64)),
//     (io.uncached === 1.U && io.ram_resp === 1.U) -> io.ram_rdata
//   ))

//   //communicate with dcache?
//   //load_miss -> ram_req/store_miss -> ?
//   val ld_miss = req === 1.U && io.cpu_wr_ctrl === 0.U && hit === 0.U && io.uncached === 0.U

//   io.ram_req := MuxCase(0.U,Seq(
//     (ld_miss === 1.U && io.ram_resp === 0.U) -> 1.U //load miss
//     (io.uncached === 1.U && io.ram_resp === 0.U) -> 1.U //access uncached
//     (req === 1.U && io.cpu_wr_ctrl === 1.U && io.ram_resp === 0.U) -> 1.U //store op
//   ))

//   //uncache just read 
//   //*!no! don't write data to dcache!but fetch data!*
//   io.uncached := Mux(req === 1.U && io.cpu_wr_ctrl === 0.U && io.cpu_addr(40) === 1.U,1.U,0.U)
//   io.ram_wr_ctrl := MuxCase(0.U,Seq(
//     ((req === 1.U && io.cpu_wr_ctrl === 0.U && io.uncached === 0.U) || io.uncached === 1.U) -> 0.U,
//     (req === 1.U && io.cpu_wr_ctrl === 1.U) -> 1.U
//   ))
//   //uncache and write operation
//   //load miss 
//   io.ram_addr := MuxCase(0.U,Seq(
//     (ld_miss === 1.U) -> Cat(io.cpu_addr(39,4),Fill(4,0.U(1.W))),//两位offset
//     (io.uncached === 1.U) -> io.cpu_addr,
//     (req === 1.U && io.cpu_wr_ctrl === 1.U) -> io.cpu_addr
//   ))
//   io.cpu_resp := MuxCase(0.U,Seq(
//     (io.cpu_wr_ctrl === 0.U && hit === 1.U) -> 1.U,//直接hit或者miss更新后hit
//     (io.uncached === 1.U && io.ram_resp === 1.U) -> 1.U,
//     (req === 1.U && cpu_wr_ctrl === 1.U && io.ram_resp === 1.U) -> 1.U //no matter store hit or miss,resp = 1
//   ))

//   io.ram_wbyte := Mux((req === 1.U && io.cpu_wr_ctrl === 1.U),io.cpu_wbyte,0.U)
//   // io.ram_wdata := Mux((req === 1.U && io.cpu_wr_ctrl === 1.U),io.cpu_wdata,0.U)

//   //update data_array
//   val cnt = RegInit(0.U(2.W)) //burst trans cnt
//   val cache_temp = data_array(index)

//   val index_temp = RegInit(0.U(6.W))//更新cache时index不变，避免分支预测写入错误index
//   index_temp := Mux(r_miss === 1.U && io.ram_resp === 0.U,index_temp,index)

//   //load miss replace cache line
//   when(io.inv_flush === 1.U){
//     V_array := VecInit(Seq.fill(depth)(0.U(1.W)))
//   }.elsewhen(ld_miss === 1.U && io.ram_beat_done === 1.U){
//     when(cnt === 1.U){
//       data_array(index_temp) := Cat(data_array(index_temp)(127,64),io.ram_rdata)
//       Tag_array(index_temp) := Tag
//       V_array(index_temp) := 1.U
//       cnt := 0.U
//     }.otherwise{
//       data_array(index_temp) := Cat(io.ram_rdata,data_array(63,0))
//       cnt := 1.U
//     }
//     //store hit
//   }.elsewhen(req === 1.U && io.cpu_wr_ctrl === 1.U && hit === 1.U && offset(5) === 1.U){
//     data_array(index) := MuxCase(0.U,Seq(
//       (io.cpu_wbyte === "b10000000".U) -> Cat(cache_temp(127.64),((cache_temp(63,0)&"h00ffffff_ffffffff".U)|(io.cpu_wdata&"hff000000_00000000".U))),
//       (io.cpu_wbyte === "b01000000".U) -> Cat(cache_temp(127,64),((cache_temp(63,0)&"hff00ffff_ffffffff".U)|(io.cpu_wdata&"h00ff0000_00000000".U))),
// 	    (io.cpu_wbyte === "b00100000".U) -> Cat(cache_temp(127,64),((cache_temp(63,0)&"hffff00ff_ffffffff".U)|(io.cpu_wdata&"h0000ff00_00000000".U))),
// 	    (io.cpu_wbyte === "b00010000".U) -> Cat(cache_temp(127,64),((cache_temp(63,0)&"hffffff00_ffffffff".U)|(io.cpu_wdata&"h000000ff_00000000".U))),
// 	    (io.cpu_wbyte === "b00001000".U) -> Cat(cache_temp(127,64),((cache_temp(63,0)&"hffffffff_00ffffff".U)|(io.cpu_wdata&"h00000000_ff000000".U))),
// 	    (io.cpu_wbyte === "b00000100".U) -> Cat(cache_temp(127,64),((cache_temp(63,0)&"hffffffff_ff00ffff".U)|(io.cpu_wdata&"h00000000_00ff0000".U))),
// 	    (io.cpu_wbyte === "b00000010".U) -> Cat(cache_temp(127,64),((cache_temp(63,0)&"hffffffff_ffff00ff".U)|(io.cpu_wdata&"h00000000_0000ff00".U))),
// 	    (io.cpu_wbyte === "b00000001".U) -> Cat(cache_temp(127,64),((cache_temp(63,0)&"hffffffff_ffffff00".U)|(io.cpu_wdata&"h00000000_000000ff".U))),
// 	    (io.cpu_wbyte === "b11000000".U) -> Cat(cache_temp(127,64),((cache_temp(63,0)&"h0000ffff_ffffffff".U)|(io.cpu_wdata&"hffff0000_00000000".U))),
// 	    (io.cpu_wbyte === "b00110000".U) -> Cat(cache_temp(127,64),((cache_temp(63,0)&"hffff0000_ffffffff".U)|(io.cpu_wdata&"h0000ffff_00000000".U))),
// 	    (io.cpu_wbyte === "b00001100".U) -> Cat(cache_temp(127,64),((cache_temp(63,0)&"hffffffff_0000ffff".U)|(io.cpu_wdata&"h00000000_ffff0000".U))),
// 	    (io.cpu_wbyte === "b00000011".U) -> Cat(cache_temp(127,64),((cache_temp(63,0)&"hffffffff_ffff0000".U)|(io.cpu_wdata&"h00000000_0000ffff".U))),
// 	    (io.cpu_wbyte === "b11110000".U) -> Cat(cache_temp(127,64),((cache_temp(63,0)&"h00000000_ffffffff".U)|(io.cpu_wdata&"hffffffff_00000000".U))),
// 	    (io.cpu_wbyte === "b00001111".U) -> Cat(cache_temp(127,64),((cache_temp(63,0)&"hffffffff_00000000".U)|(io.cpu_wdata&"h00000000_ffffffff".U))),
// 	    (io.cpu_wbyte === "b11111111".U) -> Cat(cache_temp(127,64),((cache_temp(63,0)&"h00000000_00000000".U)|(io.cpu_wdata&"hffffffff_ffffffff".U)))
//     ))
//   }.elsewhen(req === 1.U && io.cpu_wr_ctrl === 1.U && hit === 1.U && addr_offset(5) === 0.U){
//     data_array(index) := MuxCase(0.U,Seq(
//      (io.cpu_wbyte === "b10000000".U) -> Cat(((cache_temp(127,64)&"h00ffffff_ffffffff".U)|(io.cpu_wdata&"hff000000_00000000".U)),cache_temp(63,0)),
// 	   (io.cpu_wbyte === "b01000000".U) -> Cat(((cache_temp(127,64)&"hff00ffff_ffffffff".U)|(io.cpu_wdata&"h00ff0000_00000000".U)),cache_temp(63,0)),
// 	   (io.cpu_wbyte === "b00100000".U) -> Cat(((cache_temp(127,64)&"hffff00ff_ffffffff".U)|(io.cpu_wdata&"h0000ff00_00000000".U)),cache_temp(63,0)),
// 	   (io.cpu_wbyte === "b00010000".U) -> Cat(((cache_temp(127,64)&"hffffff00_ffffffff".U)|(io.cpu_wdata&"h000000ff_00000000".U)),cache_temp(63,0)),
// 	   (io.cpu_wbyte === "b00001000".U) -> Cat(((cache_temp(127,64)&"hffffffff_00ffffff".U)|(io.cpu_wdata&"h00000000_ff000000".U)),cache_temp(63,0)),
// 	   (io.cpu_wbyte === "b00000100".U) -> Cat(((cache_temp(127,64)&"hffffffff_ff00ffff".U)|(io.cpu_wdata&"h00000000_00ff0000".U)),cache_temp(63,0)),
// 	   (io.cpu_wbyte === "b00000010".U) -> Cat(((cache_temp(127,64)&"hffffffff_ffff00ff".U)|(io.cpu_wdata&"h00000000_0000ff00".U)),cache_temp(63,0)),
// 	   (io.cpu_wbyte === "b00000001".U) -> Cat(((cache_temp(127,64)&"hffffffff_ffffff00".U)|(io.cpu_wdata&"h00000000_000000ff".U)),cache_temp(63,0)),
// 	   (io.cpu_wbyte === "b11000000".U) -> Cat(((cache_temp(127,64)&"h0000ffff_ffffffff".U)|(io.cpu_wdata&"hffff0000_00000000".U)),cache_temp(63,0)),
// 	   (io.cpu_wbyte === "b00110000".U) -> Cat(((cache_temp(127,64)&"hffff0000_ffffffff".U)|(io.cpu_wdata&"h0000ffff_00000000".U)),cache_temp(63,0)),
// 	   (io.cpu_wbyte === "b00001100".U) -> Cat(((cache_temp(127,64)&"hffffffff_0000ffff".U)|(io.cpu_wdata&"h00000000_ffff0000".U)),cache_temp(63,0)),
// 	   (io.cpu_wbyte === "b00000011".U) -> Cat(((cache_temp(127,64)&"hffffffff_ffff0000".U)|(io.cpu_wdata&"h00000000_0000ffff".U)),cache_temp(63,0)),
// 	   (io.cpu_wbyte === "b11110000".U) -> Cat(((cache_temp(127,64)&"h00000000_ffffffff".U)|(io.cpu_wdata&"hffffffff_00000000".U)),cache_temp(63,0)),
// 	   (io.cpu_wbyte === "b00001111".U) -> Cat(((cache_temp(127,64)&"hffffffff_00000000".U)|(io.cpu_wdata&"h00000000_ffffffff".U)),cache_temp(63,0)),
// 	   (io.cpu_wbyte === "b11111111".U) -> Cat(((cache_temp(127,64)&"h00000000_00000000".U)|(io.cpu_wdata&"hffffffff_ffffffff".U)),cache_temp(63,0))
//     ))
//   }
// }

// //==================r_runahead end===================================
