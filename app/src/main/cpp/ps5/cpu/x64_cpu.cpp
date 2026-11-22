#include "x64_cpu.h"

#include <cstddef>
#include <cstdint>
#include <limits>

namespace anmu::ps5::cpu {
namespace {

constexpr std::uint64_t kArithmeticFlags =
    FlagCarry | FlagParity | FlagAdjust | FlagZero | FlagSign | FlagOverflow;

class Decoder final {
public:
    Decoder(const GuestMemory& memory, std::uint64_t start) noexcept
        : memory_(memory), cursor_(start) {}

    bool byte(std::uint8_t& value) noexcept {
        if (!memory_.read(cursor_, &value, sizeof(value))) {
            fault_ = true;
            return false;
        }
        if (cursor_ == std::numeric_limits<std::uint64_t>::max()) {
            fault_ = true;
            return false;
        }
        ++cursor_;
        return true;
    }

    bool unsigned_value(std::uint64_t& value, unsigned count) noexcept {
        value = 0;
        for (unsigned i = 0; i < count; ++i) {
            std::uint8_t part = 0;
            if (!byte(part)) {
                return false;
            }
            value |= static_cast<std::uint64_t>(part) << (i * 8U);
        }
        return true;
    }

    std::uint64_t cursor() const noexcept { return cursor_; }
    bool faulted() const noexcept { return fault_; }

private:
    const GuestMemory& memory_;
    std::uint64_t cursor_;
    bool fault_ = false;
};

bool add_signed(std::uint64_t base, std::int64_t delta, std::uint64_t& result) noexcept {
    if (delta >= 0) {
        const auto amount = static_cast<std::uint64_t>(delta);
        if (amount > std::numeric_limits<std::uint64_t>::max() - base) {
            return false;
        }
        result = base + amount;
        return true;
    }
    const auto amount = static_cast<std::uint64_t>(-(delta + 1)) + 1U;
    if (amount > base) {
        return false;
    }
    result = base - amount;
    return true;
}

std::uint64_t width_mask(bool wide) noexcept {
    return wide ? std::numeric_limits<std::uint64_t>::max() : 0xFFFFFFFFULL;
}

bool even_parity(std::uint8_t value) noexcept {
    value ^= static_cast<std::uint8_t>(value >> 4U);
    value &= 0x0FU;
    return ((0x9669U >> value) & 1U) != 0;
}

void update_szp(CpuState& state, std::uint64_t result, bool wide) noexcept {
    const std::uint64_t mask = width_mask(wide);
    result &= mask;
    state.rflags &= ~(FlagZero | FlagSign | FlagParity);
    if (result == 0) {
        state.rflags |= FlagZero;
    }
    const unsigned sign_bit = wide ? 63U : 31U;
    if (((result >> sign_bit) & 1U) != 0) {
        state.rflags |= FlagSign;
    }
    if (even_parity(static_cast<std::uint8_t>(result & 0xFFU))) {
        state.rflags |= FlagParity;
    }
}

void update_add_flags(CpuState& state, std::uint64_t lhs, std::uint64_t rhs,
                      std::uint64_t result, bool wide) noexcept {
    const std::uint64_t mask = width_mask(wide);
    const unsigned sign_bit = wide ? 63U : 31U;
    lhs &= mask;
    rhs &= mask;
    result &= mask;
    state.rflags &= ~kArithmeticFlags;
    if (result < lhs) {
        state.rflags |= FlagCarry;
    }
    if (((~(lhs ^ rhs) & (lhs ^ result)) >> sign_bit) & 1U) {
        state.rflags |= FlagOverflow;
    }
    if (((lhs ^ rhs ^ result) & 0x10U) != 0) {
        state.rflags |= FlagAdjust;
    }
    update_szp(state, result, wide);
}

void update_sub_flags(CpuState& state, std::uint64_t lhs, std::uint64_t rhs,
                      std::uint64_t result, bool wide) noexcept {
    const std::uint64_t mask = width_mask(wide);
    const unsigned sign_bit = wide ? 63U : 31U;
    lhs &= mask;
    rhs &= mask;
    result &= mask;
    state.rflags &= ~kArithmeticFlags;
    if (lhs < rhs) {
        state.rflags |= FlagCarry;
    }
    if ((((lhs ^ rhs) & (lhs ^ result)) >> sign_bit) & 1U) {
        state.rflags |= FlagOverflow;
    }
    if (((lhs ^ rhs ^ result) & 0x10U) != 0) {
        state.rflags |= FlagAdjust;
    }
    update_szp(state, result, wide);
}

void update_logic_flags(CpuState& state, std::uint64_t result, bool wide) noexcept {
    state.rflags &= ~kArithmeticFlags;
    update_szp(state, result, wide);
}

void write_reg(CpuState& state, unsigned index, std::uint64_t value, bool wide) noexcept {
    state.gpr[index & 15U] = wide ? value : (value & 0xFFFFFFFFULL);
}

}  // namespace

void X64Cpu::reset(std::uint64_t entry_point) noexcept {
    state_ = CpuState{};
    state_.rip = entry_point;
    state_.rflags = FlagReserved1;
}

StepResult X64Cpu::step() noexcept {
    if (state_.halted) {
        return StepResult::Halted;
    }

    Decoder decoder(memory_, state_.rip);
    std::uint8_t opcode = 0;
    if (!decoder.byte(opcode)) {
        return StepResult::MemoryFault;
    }

    bool rex_present = false;
    std::uint8_t rex = 0;
    if (opcode >= 0x40U && opcode <= 0x4FU) {
        rex_present = true;
        rex = opcode;
        if (!decoder.byte(opcode)) {
            return StepResult::MemoryFault;
        }
    }
    const bool wide = rex_present && ((rex & 0x08U) != 0);
    const std::uint64_t mask = width_mask(wide);
    const std::uint64_t next_ip = decoder.cursor();

    if (opcode == 0x90U) {  // NOP
        state_.rip = next_ip;
        return StepResult::Executed;
    }
    if (opcode == 0xF4U) {  // HLT (emulator stop)
        state_.rip = next_ip;
        state_.halted = true;
        return StepResult::Halted;
    }

    if (opcode >= 0xB8U && opcode <= 0xBFU) {  // MOV r32/64, imm
        const unsigned reg = (opcode - 0xB8U) | ((rex & 0x01U) ? 8U : 0U);
        std::uint64_t immediate = 0;
        if (!decoder.unsigned_value(immediate, wide ? 8U : 4U)) {
            return StepResult::MemoryFault;
        }
        write_reg(state_, reg, immediate, wide);
        state_.rip = decoder.cursor();
        return StepResult::Executed;
    }

    // 64-bit register stack operations (long-mode PUSH/POP).
    if (opcode >= 0x50U && opcode <= 0x5FU) {
        const unsigned reg = (opcode & 7U) | ((rex & 0x01U) ? 8U : 0U);
        if (opcode <= 0x57U) {
            if (state_.reg(Register::Rsp) < sizeof(std::uint64_t)) {
                return StepResult::MemoryFault;
            }
            const std::uint64_t new_rsp = state_.reg(Register::Rsp) - sizeof(std::uint64_t);
            const std::uint64_t value = state_.gpr[reg];
            if (!memory_.write(new_rsp, &value, sizeof(value))) {
                return StepResult::MemoryFault;
            }
            state_.reg(Register::Rsp) = new_rsp;
        } else {
            const std::uint64_t old_rsp = state_.reg(Register::Rsp);
            if (old_rsp > std::numeric_limits<std::uint64_t>::max() - sizeof(std::uint64_t)) {
                return StepResult::MemoryFault;
            }
            std::uint64_t value = 0;
            if (!memory_.read(old_rsp, &value, sizeof(value))) {
                return StepResult::MemoryFault;
            }
            state_.gpr[reg] = value;
            state_.reg(Register::Rsp) = old_rsp + sizeof(std::uint64_t);
        }
        state_.rip = next_ip;
        return StepResult::Executed;
    }

    // Near relative CALL pushes the next instruction address before branching.
    if (opcode == 0xE8U) {
        std::uint64_t raw = 0;
        if (!decoder.unsigned_value(raw, 4U)) {
            return StepResult::MemoryFault;
        }
        std::uint64_t target = 0;
        if (!add_signed(decoder.cursor(), static_cast<std::int32_t>(raw), target)) {
            return StepResult::InvalidEncoding;
        }
        const std::uint64_t old_rsp = state_.reg(Register::Rsp);
        if (old_rsp < sizeof(std::uint64_t)) {
            return StepResult::MemoryFault;
        }
        const std::uint64_t new_rsp = old_rsp - sizeof(std::uint64_t);
        const std::uint64_t return_address = decoder.cursor();
        if (!memory_.write(new_rsp, &return_address, sizeof(return_address))) {
            return StepResult::MemoryFault;
        }
        state_.reg(Register::Rsp) = new_rsp;
        state_.rip = target;
        return StepResult::Executed;
    }

    // Near RET restores the instruction pointer from the top of the guest stack.
    if (opcode == 0xC3U) {
        const std::uint64_t old_rsp = state_.reg(Register::Rsp);
        if (old_rsp > std::numeric_limits<std::uint64_t>::max() - sizeof(std::uint64_t)) {
            return StepResult::MemoryFault;
        }
        std::uint64_t return_address = 0;
        if (!memory_.read(old_rsp, &return_address, sizeof(return_address))) {
            return StepResult::MemoryFault;
        }
        state_.reg(Register::Rsp) = old_rsp + sizeof(std::uint64_t);
        state_.rip = return_address;
        return StepResult::Executed;
    }

    if (opcode == 0xEBU || opcode == 0xE9U) {  // JMP rel8/rel32
        std::uint64_t raw = 0;
        const unsigned bytes = opcode == 0xEBU ? 1U : 4U;
        if (!decoder.unsigned_value(raw, bytes)) {
            return StepResult::MemoryFault;
        }
        const std::int64_t displacement = bytes == 1U
            ? static_cast<std::int8_t>(raw)
            : static_cast<std::int32_t>(raw);
        std::uint64_t target = 0;
        if (!add_signed(decoder.cursor(), displacement, target)) {
            return StepResult::InvalidEncoding;
        }
        state_.rip = target;
        return StepResult::Executed;
    }

    if (opcode == 0x74U || opcode == 0x75U) {  // JZ/JNZ rel8
        std::uint8_t raw = 0;
        if (!decoder.byte(raw)) {
            return StepResult::MemoryFault;
        }
        const bool zero = (state_.rflags & FlagZero) != 0;
        const bool take = opcode == 0x74U ? zero : !zero;
        std::uint64_t target = decoder.cursor();
        if (take && !add_signed(target, static_cast<std::int8_t>(raw), target)) {
            return StepResult::InvalidEncoding;
        }
        state_.rip = target;
        return StepResult::Executed;
    }

    // Register-register forms only: memory ModRM operands are not implemented yet.
    const bool binary = opcode == 0x89U || opcode == 0x8BU ||
        opcode == 0x01U || opcode == 0x03U || opcode == 0x29U ||
        opcode == 0x2BU || opcode == 0x31U || opcode == 0x33U ||
        opcode == 0x39U || opcode == 0x3BU;
    if (!binary) {
        return StepResult::UnsupportedInstruction;
    }

    std::uint8_t modrm = 0;
    if (!decoder.byte(modrm)) {
        return StepResult::MemoryFault;
    }
    if ((modrm & 0xC0U) != 0xC0U) {
        return StepResult::UnsupportedInstruction;
    }

    const unsigned reg = ((modrm >> 3U) & 7U) | ((rex & 0x04U) ? 8U : 0U);
    const unsigned rm = (modrm & 7U) | ((rex & 0x01U) ? 8U : 0U);
    const unsigned destination = (opcode == 0x89U || opcode == 0x01U ||
        opcode == 0x29U || opcode == 0x31U || opcode == 0x39U) ? rm : reg;
    const unsigned source = destination == rm ? reg : rm;
    const std::uint64_t lhs = state_.gpr[destination] & mask;
    const std::uint64_t rhs = state_.gpr[source] & mask;
    const std::uint64_t following_ip = decoder.cursor();

    switch (opcode) {
        case 0x89U: case 0x8BU:
            write_reg(state_, destination, state_.gpr[source], wide);
            break;
        case 0x01U: case 0x03U: {
            const std::uint64_t result = (lhs + rhs) & mask;
            update_add_flags(state_, lhs, rhs, result, wide);
            write_reg(state_, destination, result, wide);
            break;
        }
        case 0x29U: case 0x2BU: {
            const std::uint64_t result = (lhs - rhs) & mask;
            update_sub_flags(state_, lhs, rhs, result, wide);
            write_reg(state_, destination, result, wide);
            break;
        }
        case 0x31U: case 0x33U: {
            const std::uint64_t result = lhs ^ rhs;
            update_logic_flags(state_, result, wide);
            write_reg(state_, destination, result, wide);
            break;
        }
        case 0x39U: case 0x3BU:
            update_sub_flags(state_, lhs, rhs, (lhs - rhs) & mask, wide);
            break;
        default:
            return StepResult::UnsupportedInstruction;
    }

    state_.rip = following_ip;
    return StepResult::Executed;
}

}  // namespace anmu::ps5::cpu
