// package freechips.rocketchip.rocket

// import chisel3._
// import chisel3.util.{isPow2,log2Ceil,log2Up,Decoupled,Valid}
// import freechips.rocketchip.util._
// import freechips.rocketchip.diplomacy._
// import freechips.rocketchip.amba._
// import chisel3.experimental.BaseModule

// class dcache_data_ram extends BlackBox {
//     val io = IO(new Bundle {
     
//     val        clka = Input(UInt(1.W))
//     val        ena  = Input(UInt(1.W))
//     val        wea   = Input(UInt(4.W))
//     val        addra   = Input(UInt(7.W))
//     val        dina  = Input(UInt(64.W))
//     val        douta  = Output(UInt(64.W))
  
//     })
// }

// class dcache_data  extends Module{
//     val io = IO(new Bundle {
     
//     val        en   = Input(UInt(1.W))
//     val        wen   = Input(UInt(4.W))
//     val        addr   = Input(UInt(40.W))
//     val        wdata   = Input(UInt(64.W))
//     val        rdata  = Output(UInt(64.W))
  
//     })
//     val dcache_data_ram_0 = Module(new dcache_data_ram)
//     dcache_data_ram_0.io.clka := clock.asUInt
//     dcache_data_ram_0.io.ena   := io.en
//     dcache_data_ram_0.io.wea  := io.wen
//     dcache_data_ram_0.io.addra := io.addr(11,5)
//     dcache_data_ram_0.io.dina := io.wdata
//     io.rdata     := dcache_data_ram_0.io.douta 
// }



