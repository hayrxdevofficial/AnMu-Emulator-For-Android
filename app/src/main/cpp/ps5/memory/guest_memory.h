#pragma once

#include <cstddef>
#include <cstdint>
#include <shared_mutex>
#include <vector>

namespace anmu::ps5 {

enum class MemoryPermission : std::uint8_t {
    None = 0,
    Read = 1,
    Write = 2,
    Execute = 4,
};

constexpr MemoryPermission operator|(MemoryPermission left, MemoryPermission right) noexcept {
    return static_cast<MemoryPermission>(
        static_cast<std::uint8_t>(left) | static_cast<std::uint8_t>(right));
}

constexpr bool has_permission(MemoryPermission value, MemoryPermission requested) noexcept {
    return (static_cast<std::uint8_t>(value) & static_cast<std::uint8_t>(requested)) ==
           static_cast<std::uint8_t>(requested);
}

/**
 * Small, sparse guest-memory foundation.
 *
 * This is not a complete PS5 address-space implementation. It provides checked,
 * non-overlapping mappings for early CPU/memory tests. It deliberately rejects
 * cross-region accesses and caps individual allocations to protect Android.
 */
class GuestMemory final {
public:
    explicit GuestMemory(std::size_t max_region_bytes = 64U * 1024U * 1024U);

    bool map(std::uint64_t guest_base, std::size_t size, MemoryPermission permissions);
    bool unmap(std::uint64_t guest_base);

    bool read(std::uint64_t guest_address, void* destination, std::size_t size) const;
    bool write(std::uint64_t guest_address, const void* source, std::size_t size);

    std::size_t mapped_region_count() const;
    std::size_t max_region_bytes() const noexcept { return max_region_bytes_; }

private:
    struct Region {
        std::uint64_t base;
        std::vector<std::uint8_t> bytes;
        MemoryPermission permissions;
    };

    using RegionList = std::vector<Region>;

    RegionList::const_iterator find_region_locked(std::uint64_t address) const;
    RegionList::iterator find_region_locked(std::uint64_t address);

    const std::size_t max_region_bytes_;
    mutable std::shared_mutex mutex_;
    RegionList regions_;
};

}  // namespace anmu::ps5
