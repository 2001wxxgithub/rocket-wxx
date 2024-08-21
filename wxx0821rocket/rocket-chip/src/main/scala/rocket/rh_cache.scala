// //r_runahead cache
// //==================r_runahead strat===================================

// package freechips.rocketchip.rocket

// import chisel3._
// import chisel3.util.{isPow2,log2Ceil,log2Up,Decoupled,Valid}
// import freechips.rocketchip.util._
// import freechips.rocketchip.diplomacy._
// import freechips.rocketchip.amba._
// import chisel3.experimental.BaseModule

// class data_cache extend Module {
//     val io = IO(new Bundle {
//         val port = new axi_sram_port

//         val s2_stall = Output(Bool())
//         val s1_wr = Output(Bool())

//         val v_addr = Output(UInt(40.W))
//         val p_addr = Output(UInt(40.W))
//         //tlb查找
//         //mmu?
//         val tlb_req = Output(Bool())
//         //stage1 excp message send to core
//         val data_wbyte = Input(UInt(8.W))//8 128bit
//     })

//     val find_state = RegInit("b1100".U(5.W))
//     val ac_find_state = Wire(UInt(5.W))
//     val wr_count = RegInit(0.U(3.W))
//     val rd_count = RegInit(0.U(3.W))
//     val wait_data = RegInit(0.U(64.W))

//     val tag_0 = Module(new tag_array).io
//     val tag_1 = Module(new tag_array).io

//     val lru = RegInit(VecInit(Seq.fill(128)(0.U(1.W))))
//     val w0_dbit = RegInit(VecInit(Seq.fill(128)(0.U(1.W))))
//     val w1_dbit = RegInit(VecInit(Seq.fill(128)(0.U(1.W))))

//     val w0_wen = Wire(Vec(8,UInt(1.W)))//8
//     val w1_wen = Wire(Vec(8,UInt(1.W)))

//     val s1_sram_addr_r = RegInit(0.U(40.W))
//     val s1_sram_cache_r = RegInit(0.U(1.W))
//     val s1_sram_wdata_r = RegInit(0.U(64.W))
//     val s1_sram_size_r = RegInit(0.U(2.W))//2
//     val s1_sram_wrctrl_r = RegInit(0.U(1.W))
//     val s1_sram_req_r = RegInit(0.U.asBool)

//     val s1_sram_hit0_r = RegInit(0.U.asBool)
//     val s1_sram_hit1_r = RegInit(0.U.asBool)
//     val s1_sram_v0_r = RegInit(0.U.asBool)
//     val s1_sram_v1_r = RegInit(0.U.asBool)

//     val s1_wbyte_r = RegInit(0.U(8.W))

//     val dbit_victim = RegInit(0.U(1.W))
//     val data_w0 = VecInit(Seq.fill(8)(Module(new data_array).io))//8 port
//     val data_w1 = VecInit(Seq.fill(8)(Module(new data_array).io))
//     val hit_0_r = RegInit(0.U(1.W))
//     val hit_1_r = RegInit(0.U(1.W))

//     val s_miss_acram_rd_3 = "b00000".U
//     val s_acram_rd_0 = "b00001".U
//     val s_acram_rd_1 = "b00010".U
//     val s_acram_wr_0 = "b00011".U
//     val s_acram_wr_1 = "b00100".U
//     val s_acram_wr_2 = "b00101".U

//     val s_miss_wr_wr_1 = "b00110".U
//     val s_miss_wr_wr_2 = "b00111".U
//     val s_miss_acram_rd_2 = "b01000".U

//     val s_miss_wr_rd_0 = "b01001".U
//     val s_miss_wr_rd_1 = "b01010".U
//     val s_miss_wr_rd_2 = "b01011".U
//     val s_miss_acram_rd_0 = "b01100".U
//     val s_miss_acram_rd_1 = "b01101".U
//     val s_miss_rd_update = "b01110".U
//     val s_miss_wr_wr_0 = "b01111".U
//     val s_miss_wr_update = "b10000".U

//     val s_data_ready = "b11000".U
//     val s_lookup = "b11001".U

//     val s_all_inv = "b10001".U
//     val all_inv_count =RegInit(0.U(7.W))

//     val s2_stall = Wire(Bool())
//     val s2_flush = 0.U.asBool
//     io.s2_stall := s2_stall

//     val s2_addr_sames1 = Wire(Bool())// store and next load
//     val stage2_wdata_reg = RegEnable(Mux(s2_flush,0.U,s1_sram_wdata_r),0.U,s2_stall)
   
//     val s_ready_lookup  = Wire(UInt(5.W))
//     val s_lookup_for_less = Wire(UInt(5.W))
//     val s2_sram_wr_r = RegInit(0.U.asBool)
//     val s2_sram_cache_r = RegInit(0.U.asBool)

//     val s1_addr_req_nsame = RegInit(0.U.asBool)
//     val s1_stall_reg = RegInit(0.U.asBool)
//     s1_stall_reg := io.port.sram_req.asBool//delay a cycle

//     val wrac_done_reg = RegInit(0.U.asBool)

//     s1_addr_req_nsame := s1_sram_addr_r =/= io.port.sram_addr

//     s1_sram_addr_r := Mux(io.port.sram_req.asBool,io.port.sram_addr,s1_sram_addr_r)
//     s1_sram_cache_r := Mux(io.port.sram_req.asBool,io.port.sram_cache,s1_sram_cache_r)
//     s1_sram_wdata_r := Mux(io.port.sram_req.asBool,io.port.sram_wdata,s1_sram_wdata_r)
//     s1_sram_size_r := Mux(io.port.sram_req.asBool,io.port.sram_size,s1_sram_size_r)
//     s1_sram_wrctrl_r := Mux(io.port.sram_req.asBool,io.port.sram_wr,s1_sram_wrctrl_r)
//     s1_sram_req_r   := Mux(io.port.sram_req.asBool,1.U,Mux(s2_stall,0.U,s1_sram_req_r))

//     s1_sram_hit0_r := Mux(io.port.sram_req.asBool,tag_0.hit,s1_sram_hit0_r)
//     s1_sram_hit1_r := Mux(io.port.sram_req.asBool,tag_1.hit,s1_sram_hit1_r)
//     s1_sram_v0_r := Mux(io.port.sram_req.asBool,tag_0.valid,s1_sram_v0_r)
//     s1_sram_v0_r := Mux(io.port.sram_req.asBool,tag_1.valid,s1_sram_v1_r)

//     w0_dbit(s1_sram_addr_r(11,5)) := Mux(s_lookup_for_less === s_lookup && s1_sram_wrctrl_r.asBool && s1_sram_hit0_r && s1_sram_v0_r,1.U,
//         Mux(work_state === s_miss_rd_update && lru(s1_sram_addr_r(11,5)) === 0.U,0.U,
//         Mux(work_state === s_miss_wr_update && lru(s1_sram_addr_r(11,5)) === 0.U,1.U,w0_dbit(s1_sram_addr_r(11,5)))))//有没有脏数据，只需要在这些情况进行更新



//     dbit_victim := Mux(lru(s1_sram_addr_r(11,5)) === 0.U,w0_dbit(s1_sram_addr_r(11,5)),w1_dbit(s1_sram_addr_r(11,5)))//index

//     tag_0.op := 0.U
//     tag_1.op := 0.U
//     //store pipe
//     tag_0.waddr := s1_sram_addr_r
//     tag_1.waddr := s1_sram_addr_r
//     //load
//     tag_0.raddr := io.port.sram_addr
//     tag_1.raddr := io.port.sram_addr

//     //memory mapping addr?
    
//     lru(s1_sram_addr_r(11,5)) := Mux(s_lookup_for_less === s_lookup,
//         Mux(s1_sram_hit0_r && s1_sram_v0_r,1.U.asBool,
//         Mux(s1_sram_hit1_r && s1_sram_v1_r,0.U.asBool,lru(s1_sram_addr_r(11,5)))),
//         Mux(work_state === s_miss_rd_update||work_state === s_miss_wr_update,~lru(s1_sram_addr_r(11,5)), lru(s1_sram_addr_r(11,5))))
    
//     val hit = (s1_sram_hit0_r && s1_sram_v0_r) ||
//         (s1_sram_hit1_r && s1_sram_v1_r)

//     s_ready_lookup :=  Mux(hit,Mux(s1_sram_req_r.asBool,Mux(s1_sram_wrctrl_r.asBool,
//             Mux(s1_sram_cache_r.asBool,s_lookup,s_acram_rd_0),Mux(s1_sram_cache_r.asBool,s_lookup,s_acram_rd_0)),s_lookup),
//             Mux(s1_sram_req_r.asBool,Mux(!s1_sram_cache_r.asBool,Mux(s1_sram_wrctrl_r.asBool,s_acram_wr_0,s_acram_rd_0),
//             Mux(dbit_victim.asBool,s_miss_wr_rd_0,Mux(s1_sram_wrctrl_r.asBool,s_miss_acram_rd_2,s_miss_acram_rd_0))),s_lookup))

//     val s_ready_lookup_should_be = Mux(hit,Mux(s1_sram_req_reg.asBool,Mux(s1_sram_cache_r.asBool,s_lookup,0.U),s_lookup),
//             Mux(s1_sram_req_r.asBool,0.U,s_lookup))

//     access_work_state := MuxLookup(work_state,work_state,Seq(
//         s_acram_rd_0 -> Mux(io.port.arready.asBool, s_acram_rd_1,work_state),
//         s_acram_rd_1 -> Mux(io.port.rvalid.asBool,s_data_ready,work_state),
//         s_data_ready        -> s_ready_lookup,
//         s_acram_wr_0-> Mux(io.port.awready.asBool,s_acram_wr_1,work_state),
//         s_acram_wr_1-> Mux(io.port.wready.asBool,s_acram_wr_2,work_state),
//         s_acram_wr_2-> s_data_ready,
//         s_lookup            -> s_ready_lookup,
//         s_miss_acram_rd_0 -> Mux(io.port.arready.asBool,s_miss_acram_rd_1,work_state),
//         s_miss_acram_rd_1 -> Mux(io.port.rlast.asBool && io.port.rvalid.asBool,Mux(s1_sram_wrctrl_r.asBool,s_miss_wr_update,s_miss_rd_update),work_state),
//         s_miss_rd_update    -> s_data_ready,
//         s_miss_wr_rd_0   -> Mux(io.port.awready.asBool,s_miss_wr_rd_1,work_state),
//         s_miss_wr_rd_1   -> Mux(io.port.wready.asBool && wr_count === "b111".U,s_miss_wr_rd_2,work_state),
//         s_miss_wr_rd_2   -> Mux(io.port.bvalid.asBool,s_miss_acram_rd_0,work_state),
//         s_miss_acram_rd_2 -> Mux(io.port.arready.asBool,s_miss_acram_rd_3,work_state),
//         s_miss_acram_rd_3 -> Mux(io.port.rvalid.asBool  && io.port.rlast.asBool,s_miss_wr_update,work_state),
//         s_miss_wr_update   -> s_data_ready,
//         s_miss_wr_wr_0  -> Mux(io.port.awready.asBool,s_miss_wr_wr_1,work_state),
//         s_miss_wr_wr_1  -> Mux(io.port.wready.asBool && wr_count === "b111".U , s_miss_wr_wr_2,work_state),
//         s_miss_wr_wr_2  -> Mux(io.port.bvalid.asBool,s_miss_acram_rd_2,work_state)))

//     s_lookup_for_less := MuxLookup(work_state,work_state,Seq(
//         s_data_ready        -> s_ready_lookup_should_be,
//         s_lookup            -> s_ready_lookup_should_be))

//     //s1 打一拍 接收数据
//     work_state := Mux(s1_excp =/= 0.U,s_lookup,access_work_state)
//     //等待一下数据 axi写完不立刻读
//     wait_data := Mux(work_state === s_acram_rd_1 && io.port.rvalid.asBool,io.port.rdata,
//     Mux(work_state === s_miss_acram_rd_1 && io.port.rvalid.asBool && rd_count === s1_sram_addr_r(5,3),io.port.rdata,wait_data))

//     wr_count := Mux(work_state === s_miss_wr_rd_1,Mux(io.port.wready.asBool,Mux(wr_count === "b111".U,0.U,wr_count+1.U),wr_count),
//         Mux(work_state === s_miss_wr_wr_1,Mux(io.port.wready.asBool,Mux(wr_count === "b111".U,0.U,wr_count+1.U),wr_count),wr_count))
//     rd_count := Mux(work_state === s_miss_acram_rd_1,Mux(io.port.rvalid.asBool && io.port.rlast.asBool, 0.U, Mux(io.port.rvalid.asBool,rd_count+1.U,rd_count)),
//         Mux(work_state === s_miss_acram_rd_3,Mux(io.port.rvalid.asBool && io.port.rlast.asBool, 0.U, Mux(io.port.rvalid.asBool,rd_count+1.U,rd_count)),rd_count))
    
//     val hit0 = (s1_sram_hit0_r && s1_sram_v0_r) 
   
//     val hit1 = (s1_sram_hit1_r && s1_sram_v1_r)
//     s2_sram_cache_r := Mux(s2_flush,0.U,Mux(s2_stall,s1_sram_cache_r,s2_sram_cache_r))   
//     val s2_sram_req_r = RegInit(0.U.asBool)
//     s2_sram_req_r := Mux(s2_flush,0.U,Mux(s2_stall,s1_sram_req_r,s2_sram_req_r))
//     s2_sram_wr_r := Mux(s2_flush,0.U,Mux(s2_stall,s1_sram_wr_r,s2_sram_wr_r))

//     val s2_hit0_r = RegInit(0.U.asBool)
//     s2_hit0_r := Mux(s2_flush,0.U,Mux(s2_stall,hit0,s2_hit0_r))

//     val s2_hit1_r = RegInit(0.U.asBool)
//     s2_hit1_r := Mux(s2_flush,0.U,Mux(s2_stall,hit1,s2_hit1_r))
    
//     val s2_hit_reg  = RegInit(0.U.asBool)
//     s2_hit_reg := Mux(s2_flush,0.U,Mux(s2_stall,hit,s2_hit_reg))
    
//     s2_excp := Mux(s2_stall,s1_excp,s2_excp)
//     io.s1_tlb_excp := s1_excp

//     val word_select0 = data_w0(s2_sram_addr_r(5,3)).rdata
//     val word_select1 = data_w0(s2_sram_addr_r(5,3)).rdata

//     val hit_word = Mux(s2_hit0_r.asBool,word_select0,word_select1) 
//     val wb_word0 = data_w0(wr_count).rdata
//     val wb_word1 = data_w1(wr_count).rdata
//     val wen_way0_wire = Wire(Vec(8,(UInt(4.W))))
//     val wen_way1_wire = Wire(Vec(8,(UInt(4.W))))
//     val writeback_data = Mux(lru(s1_sram_addr_r(11,5)).asBool,wb_word1,wb_word0)
//     val w0_burst_rd_wen = (work_state === s_miss_acram_rd_1 || work_state === s_miss_acram_rd_3) && io.port.rvalid.asBool && lru(s1_sram_addr_r(11,5)) === 0.U
//     val w1_burst_rd_wen = (work_state === s_miss_acram_rd_1 || work_state === s_miss_acram_rd_3) && io.port.rvalid.asBool && lru(s1_sram_addr_r(11,5)) === 1.U

//      for(i <- 0 to 7 ) {
//         data_w0(i).addr := s1_sram_addr_r
//         data_w0(i).wdata := Mux(work_state === s_miss_wr_update || s_lookup_for_less === s_lookup ,s1_sram_wdata_r,Mux(work_state === s_miss_acram_rd_1 ||work_state === s_miss_acram_rd_3,io.port.rdata,0.U))
//         data_w0(i).en := 1.U
//         data_w0(i).wen := wen_way0_wire(i)
//         data_w1(i).addr := s1_sram_addr_r
//         data_w1(i).wdata := Mux(work_state === s_miss_wr_update || s_lookup_for_less === s_lookup ,s1_sram_wdata_r,Mux(work_state === s_miss_acram_rd_1 ||work_state === s_miss_acram_rd_3,io.port.rdata,0.U))
//         data_w1(i).en := 1.U
//         data_w1(i).wen := wen_way1_wire(i)   
//     }

//     for(i <- 0 to 7) {
//         wen_way0_wire(i) :=  Mux( s1_sram_addr_r(4,2) === i.asUInt && ((s_lookup_for_less_ === s_lookup && s1_sram_wrctrl_r.asBool && s1_sram_hit0_reg && s1_sram_v0_r)||
//             (work_state === s_miss_wr_update  && lru(s1_sram_addr_r(11,5)) === 0.U)),s1_wbyte_r,Cat(Seq.fill(4)(way0_wen(i))))
//         wen_way1_wire(i) :=  Mux( s1_sram_addr_r(4,2) === i.asUInt && ((s_lookup_for_less === s_lookup && s1_sram_wrctrl_r.asBool && s1_sram_hit1_reg && s1_sram_v1_r)||
//             (work_state === s_miss_wr_update  && lru(s1_sram_addr_r(11,5)) === 1.U)),s1_wbyte_r,Cat(Seq.fill(4)(way1_wen(i))))
//     }
//     for(i <- 0 to 7) {
//         way0_wen(i)  := Mux(i.asUInt === rd_count,w0_burst_rd_wen ,0.U) 
//         way1_wen(i)  := Mux(i.asUInt === rd_count,w1_burst_rd_wen ,0.U) 
//     }

//     val cache_wdata = Mux(work_state === s_miss_acram_rd_1 || work_state === s_miss_acram_rd_3,io.port.rdata,
//         Mux(work_state === s_lookup,s1_sram_wdata_r,0.U))
  
    
//     tag_0.wen := Mux((work_state === s_miss_acram_rd_1 ||work_state === s_miss_acram_rd_3 ) && lru(s1_sram_addr_r(11,5)) === 0.U,1.U,0.U)//最近使用的是cache 1
//     tag_1.wen := Mux((work_state === s_miss_acram_rd_1 ||work_state === s_miss_acram_rd_3 ) && lru(s1_sram_addr_r(11,5)) === 1.U,1.U,0.U)//最近使用的是cache 0
//     tag_0.wdata := Mux((work_state === s_miss_acram_rd_1 ||work_state === s_miss_acram_rd_3 ) ,Cat(1.U(1.W),s1_sram_addr_r(39,12)),0.U)
//     tag_1.wdata := Mux((work_state === s_miss_acram_rd_1 ||work_state === s_miss_acram_rd_3 ) ,Cat(1.U(1.W),s1_sram_addr_r(39,12)),0.U)
      
// }
