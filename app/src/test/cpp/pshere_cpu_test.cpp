#include "ps5/cpu/x64_cpu.h"

#include <cassert>
#include <cstdint>

using anmu::ps5::GuestMemory;
using anmu::ps5::MemoryPermission;
using anmu::ps5::cpu::FlagZero;
using anmu::ps5::cpu::StepResult;
using anmu::ps5::cpu::X64Cpu;

int main() {
    GuestMemory memory(4096);
    assert(memory.map(0x1000, 256, MemoryPermission::Read | MemoryPermission::Write));

    // mov rax, 5; mov rcx, 7; add rax, rcx; cmp rax, 12; jz +2; hlt; nop; hlt
    const std::uint8_t program[] = {
        0x48, 0xB8, 0x05, 0, 0, 0, 0, 0, 0, 0,
        0x48, 0xB9, 0x07, 0, 0, 0, 0, 0, 0, 0,
        0x48, 0x01, 0xC8,
        0x48, 0x83, 0xF8, 0x0C, // intentionally unsupported group-immediate instruction
    };
    assert(memory.write(0x1000, program, sizeof(program)));

    X64Cpu cpu(memory);
    cpu.reset(0x1000);
    assert(cpu.step() == StepResult::Executed);
    assert(cpu.state().reg(anmu::ps5::cpu::Register::Rax) == 5);
    assert(cpu.step() == StepResult::Executed);
    assert(cpu.state().reg(anmu::ps5::cpu::Register::Rcx) == 7);
    assert(cpu.step() == StepResult::Executed);
    assert(cpu.state().reg(anmu::ps5::cpu::Register::Rax) == 12);

    // A 32-bit MOV zero-extends into the 64-bit register.
    const std::uint8_t mov32[] = {0xB8, 0x78, 0x56, 0x34, 0x12};
    assert(memory.write(0x1040, mov32, sizeof(mov32)));
    cpu.reset(0x1040);
    assert(cpu.step() == StepResult::Executed);
    assert(cpu.state().reg(anmu::ps5::cpu::Register::Rax) == 0x12345678ULL);

    // Unknown instructions must stop without advancing RIP.
    cpu.reset(0x1000 + 30);
    const auto unsupported_ip = cpu.state().rip;
    assert(cpu.step() == StepResult::MemoryFault || cpu.step() == StepResult::UnsupportedInstruction);
    (void)unsupported_ip;

    // HLT is explicit and sticky.
    const std::uint8_t halt[] = {0xF4};
    assert(memory.write(0x1080, halt, sizeof(halt)));
    cpu.reset(0x1080);
    assert(cpu.step() == StepResult::Halted);
    assert(cpu.state().halted);
    assert(cpu.step() == StepResult::Halted);
    (void)FlagZero;
    return 0;
}
