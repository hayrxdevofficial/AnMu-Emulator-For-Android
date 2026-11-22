#include "ps5/cpu/x64_cpu.h"

#include <cassert>
#include <cstdint>

using anmu::ps5::GuestMemory;
using anmu::ps5::MemoryPermission;
using anmu::ps5::cpu::Register;
using anmu::ps5::cpu::StepResult;
using anmu::ps5::cpu::X64Cpu;

int main() {
    GuestMemory memory(4096);
    assert(memory.map(0x1000, 256, MemoryPermission::Read | MemoryPermission::Write));

    // mov rax,5; mov rcx,7; add rax,rcx; mov rdx,12;
    // cmp rax,rdx; jz +1; hlt; hlt
    const std::uint8_t program[] = {
        0x48, 0xB8, 0x05, 0, 0, 0, 0, 0, 0, 0,
        0x48, 0xB9, 0x07, 0, 0, 0, 0, 0, 0, 0,
        0x48, 0x01, 0xC8,
        0x48, 0xBA, 0x0C, 0, 0, 0, 0, 0, 0, 0,
        0x48, 0x39, 0xD0,
        0x74, 0x01,
        0xF4,
        0xF4
    };
    assert(memory.write(0x1000, program, sizeof(program)));

    X64Cpu cpu(memory);
    cpu.reset(0x1000);
    assert(cpu.step() == StepResult::Executed);
    assert(cpu.state().reg(Register::Rax) == 5);
    assert(cpu.step() == StepResult::Executed);
    assert(cpu.state().reg(Register::Rcx) == 7);
    assert(cpu.step() == StepResult::Executed);
    assert(cpu.state().reg(Register::Rax) == 12);
    assert(cpu.step() == StepResult::Executed);
    assert(cpu.state().reg(Register::Rdx) == 12);
    assert(cpu.step() == StepResult::Executed);
    assert((cpu.state().rflags & anmu::ps5::cpu::FlagZero) != 0);
    assert(cpu.step() == StepResult::Executed);
    assert(cpu.state().rip == 0x1027);
    assert(cpu.step() == StepResult::Halted);
    assert(cpu.state().halted);
    assert(cpu.step() == StepResult::Halted);

    // A 32-bit MOV zero-extends into the corresponding 64-bit register.
    const std::uint8_t mov32[] = {0xB8, 0x78, 0x56, 0x34, 0x12};
    assert(memory.write(0x1040, mov32, sizeof(mov32)));
    cpu.reset(0x1040);
    assert(cpu.step() == StepResult::Executed);
    assert(cpu.state().reg(Register::Rax) == 0x12345678ULL);

    // Unknown opcodes are rejected without changing architectural state.
    const std::uint8_t unknown[] = {0x0F, 0xFF};
    assert(memory.write(0x1060, unknown, sizeof(unknown)));
    cpu.reset(0x1060);
    assert(cpu.step() == StepResult::UnsupportedInstruction);
    assert(cpu.state().rip == 0x1060);

    // An instruction fetch outside mapped guest memory is a memory fault.
    cpu.reset(0x9000);
    assert(cpu.step() == StepResult::MemoryFault);
    assert(cpu.state().rip == 0x9000);
    return 0;
}
