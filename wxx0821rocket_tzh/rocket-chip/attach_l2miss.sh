#!/bin/bash

# 清理之前的构建
make clean

# 编译 Verilog 并记录输出
make verilog CONFIG=RocketConfig | tee make_verilog.log

# 进入 gen-collateral 目录并修改 queue56.v 文件
sed -i 's/assign io_deq_bits_hit = _ram_ext_R0_data\[116\];/assign io_deq_bits_hit = io_enq_bits_hit;/' /home/rain2/lean/chipyard/sims/verilator/generated-src/chipyard.harness.TestHarness.RocketConfig/gen-collateral/Queue_56.sv
sed -i 's/io_enq_bits_hit, 73'\''hFF/74'\''hFF/' /home/rain2/lean/chipyard/sims/verilator/generated-src/chipyard.harness.TestHarness.RocketConfig/gen-collateral/Queue_56.sv

# 运行模拟
make sim-rain

# 修改 VTestDriver.mk 文件
sed -i 's/\/VTestDriver.h/VTestDriver.h/' /home/rain2/lean/chipyard/sims/verilator/generated-src/chipyard.harness.TestHarness.RocketConfig/chipyard.harness.TestHarness.RocketConfig/VTestDriver.mk

make sim-rain

# 复制 simulator 文件
cp ./generated-src/chipyard.harness.TestHarness.RocketConfig/chipyard.harness.TestHarness.RocketConfig/simulator-chipyard.harness-RocketConfig-rain .

# 运行测试二进制
make run-binary-rain CONFIG=RocketConfig BINARY=/home/rain2/lean/chipyard/generators/rocket-chip/test/hello.riscv | tee make.log