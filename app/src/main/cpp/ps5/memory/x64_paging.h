#pragma once

#include "guest_memory.h"

#include <cstddef>
#include <cstdint>

namespace anmu::ps5 {

enum class PageAccess : std::uint8_t {
    Read,
    Write,
    Execute,
};

/**
 * Four-level x86-64 (48-bit canonical VA) page-table walker.
 *
 * Page-table pages are allocated from a caller-provided, pre-mapped RW physical
 * arena. Leaf mappings refer to caller-provided physical pages. This is a
 * foundation only: no TLB, PCID, huge pages, user/supervisor mode, or hardware
 * page-fault delivery is implemented.
 */
class X64PageTable final {
public:
    static constexpr std::uint64_t kPageSize = 4096;
    static constexpr std::uint64_t kAddressMask = 0x000FFFFFFFFFF000ULL;

    X64PageTable(
        GuestMemory& memory,
        std::uint64_t root_physical,
        std::uint64_t table_arena_base,
        std::size_t table_arena_size);

    bool valid() const noexcept { return valid_; }
    std::uint64_t root_physical() const noexcept { return root_physical_; }

    bool map_page(
        std::uint64_t virtual_address,
        std::uint64_t physical_address,
        bool writable,
        bool executable);

    bool unmap_page(std::uint64_t virtual_address);

    bool translate(
        std::uint64_t virtual_address,
        PageAccess access,
        std::uint64_t& physical_address) const;

    std::size_t allocated_table_pages() const noexcept { return allocated_pages_; }

private:
    bool canonical(std::uint64_t address) const noexcept;
    bool read_entry(std::uint64_t table, std::size_t index, std::uint64_t& entry) const;
    bool write_entry(std::uint64_t table, std::size_t index, std::uint64_t entry);
    bool next_table(std::uint64_t parent, std::size_t index, std::uint64_t& child);
    bool allocate_table(std::uint64_t& physical);

    GuestMemory& memory_;
    const std::uint64_t root_physical_;
    const std::uint64_t arena_end_;
    std::uint64_t next_free_;
    std::size_t allocated_pages_ = 0;
    bool valid_ = false;
};

}  // namespace anmu::ps5
