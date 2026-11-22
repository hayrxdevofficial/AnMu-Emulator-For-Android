#pragma once

#include "ps5/memory/guest_memory.h"

#include <array>
#include <cstdint>

namespace anmu::ps5::cpu {

enum class StepResult : std::uint8_t {
    Executed,
    Halted,
    UnsupportedInstruction,
    MemoryFault,
    InvalidEncoding,
};

enum class Register : std::uint8_t {
    Rax, Rcx, Rdx, Rbx, Rsp, Rbp, Rsi, Rdi,
    R8, R9, R10, R11, R12, R13, R14, R15
};

constexpr std::uint64_t FlagCarry = 1ULL << 0U;
constexpr std::uint64_t FlagParity = 1ULL << 2U;
constexpr std::uint64_t FlagAdjust = 1ULL << 4U;
constexpr std::uint64_t FlagZero = 1ULL << 6U;
constexpr std::uint64_t FlagSign = 1ULL << 7U;
constexpr std::uint64_t FlagTrap = 1ULL << 8U;
constexpr std::uint64_t FlagInterrupt = 1ULL << 9U;
constexpr std::uint64_t FlagDirection = 1ULL << 10U;
constexpr std::uint64_t FlagOverflow = 1ULL << 11U;
constexpr std::uint64_t FlagReserved1 = 1ULL << 1U;

struct CpuState final {
    std::array<std::uint64_t, 16> gpr{};
    std::uint64_t rip = 0;
    std::uint64_t rflags = FlagReserved1;
    bool halted = false;

    std::uint64_t& reg(Register index) noexcept {
        return gpr[static_cast<std::uint8_t>(index)];
    }
    const std::uint64_t& reg(Register index) const noexcept {
        return gpr[static_cast<std::uint8_t>(index)];
    }
};

class X64Cpu final {
public:
    explicit X64Cpu(GuestMemory& memory) noexcept : memory_(memory) {}

    CpuState& state() noexcept { return state_; }
    const CpuState& state() const noexcept { return state_; }
    void reset(std::uint64_t entry_point) noexcept;
    StepResult step() noexcept;

private:
    GuestMemory& memory_;
    CpuState state_{};
};

}  // namespace anmu::ps5::cpu
