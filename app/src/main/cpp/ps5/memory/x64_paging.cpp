#include "x64_paging.h"

#include <cstring>
#include <limits>

namespace anmu::ps5 {
namespace {
constexpr std::uint64_t kPresent = 1ULL;
constexpr std::uint64_t kWritable = 1ULL << 1U;
constexpr std::uint64_t kNoExecute = 1ULL << 63U;
constexpr std::uint64_t kPhysicalLimit = 1ULL << 52U;
}

X64PageTable::X64PageTable(
    GuestMemory& memory,
    std::uint64_t root_physical,
    std::uint64_t table_arena_base,
    std::size_t table_arena_size)
    : memory_(memory),
      root_physical_(root_physical),
      arena_end_(table_arena_size <= std::numeric_limits<std::uint64_t>::max() - table_arena_base
                     ? table_arena_base + static_cast<std::uint64_t>(table_arena_size)
                     : 0),
      next_free_(table_arena_base) {
    if (table_arena_size < kPageSize || arena_end_ == 0 ||
        (root_physical_ & (kPageSize - 1U)) != 0 ||
        (table_arena_base & (kPageSize - 1U)) != 0 ||
        (table_arena_size % kPageSize) != 0 ||
        root_physical_ < table_arena_base ||
        root_physical_ > arena_end_ - kPageSize ||
        root_physical_ >= kPhysicalLimit) {
        return;
    }

    next_free_ = root_physical_ + kPageSize;
    std::uint8_t zero[kPageSize] = {};
    if (!memory_.write(root_physical_, zero, sizeof(zero))) {
        return;
    }
    allocated_pages_ = 1;
    valid_ = true;
}

bool X64PageTable::canonical(std::uint64_t address) const noexcept {
    const std::uint64_t upper = address >> 48U;
    const bool sign = ((address >> 47U) & 1U) != 0;
    return sign ? upper == 0xFFFFU : upper == 0;
}

bool X64PageTable::read_entry(
    std::uint64_t table,
    std::size_t index,
    std::uint64_t& entry) const {
    return index < 512U &&
           memory_.read(table + static_cast<std::uint64_t>(index) * sizeof(entry),
                        &entry, sizeof(entry));
}

bool X64PageTable::write_entry(
    std::uint64_t table,
    std::size_t index,
    std::uint64_t entry) {
    return index < 512U &&
           memory_.write(table + static_cast<std::uint64_t>(index) * sizeof(entry),
                         &entry, sizeof(entry));
}

bool X64PageTable::allocate_table(std::uint64_t& physical) {
    if (next_free_ > arena_end_ || arena_end_ - next_free_ < kPageSize) {
        return false;
    }
    physical = next_free_;
    std::uint8_t zero[kPageSize] = {};
    if (!memory_.write(physical, zero, sizeof(zero))) {
        return false;
    }
    next_free_ += kPageSize;
    ++allocated_pages_;
    return true;
}

bool X64PageTable::next_table(
    std::uint64_t parent,
    std::size_t index,
    std::uint64_t& child) {
    std::uint64_t entry = 0;
    if (!read_entry(parent, index, entry)) {
        return false;
    }
    if ((entry & kPresent) == 0) {
        if (!allocate_table(child) ||
            !write_entry(parent, index, (child & kAddressMask) | kPresent | kWritable)) {
            return false;
        }
        return true;
    }
    if ((entry & (1ULL << 7U)) != 0) {
        return false;  // Huge-page entries are deliberately unsupported.
    }
    child = entry & kAddressMask;
    return child != 0;
}

bool X64PageTable::map_page(
    std::uint64_t virtual_address,
    std::uint64_t physical_address,
    bool writable,
    bool executable) {
    if (!valid_ || !canonical(virtual_address) ||
        (virtual_address & (kPageSize - 1U)) != 0 ||
        (physical_address & (kPageSize - 1U)) != 0 ||
        physical_address >= kPhysicalLimit) {
        return false;
    }

    std::uint8_t probe = 0;
    if (!memory_.read(physical_address, &probe, sizeof(probe))) {
        return false;
    }

    const std::size_t pml4 = static_cast<std::size_t>((virtual_address >> 39U) & 0x1FFU);
    const std::size_t pdpt = static_cast<std::size_t>((virtual_address >> 30U) & 0x1FFU);
    const std::size_t pd = static_cast<std::size_t>((virtual_address >> 21U) & 0x1FFU);
    const std::size_t pt = static_cast<std::size_t>((virtual_address >> 12U) & 0x1FFU);

    std::uint64_t l3 = 0, l2 = 0, l1 = 0;
    if (!next_table(root_physical_, pml4, l3) ||
        !next_table(l3, pdpt, l2) ||
        !next_table(l2, pd, l1)) {
        return false;
    }

    std::uint64_t existing = 0;
    if (!read_entry(l1, pt, existing) || (existing & kPresent) != 0) {
        return false;
    }

    std::uint64_t flags = kPresent;
    if (writable) flags |= kWritable;
    if (!executable) flags |= kNoExecute;
    return write_entry(l1, pt, (physical_address & kAddressMask) | flags);
}

bool X64PageTable::unmap_page(std::uint64_t virtual_address) {
    if (!valid_ || !canonical(virtual_address) ||
        (virtual_address & (kPageSize - 1U)) != 0) {
        return false;
    }
    const std::size_t indexes[] = {
        static_cast<std::size_t>((virtual_address >> 39U) & 0x1FFU),
        static_cast<std::size_t>((virtual_address >> 30U) & 0x1FFU),
        static_cast<std::size_t>((virtual_address >> 21U) & 0x1FFU),
        static_cast<std::size_t>((virtual_address >> 12U) & 0x1FFU)
    };
    std::uint64_t table = root_physical_;
    for (std::size_t level = 0; level < 3U; ++level) {
        std::uint64_t entry = 0;
        if (!read_entry(table, indexes[level], entry) || (entry & kPresent) == 0 ||
            (entry & (1ULL << 7U)) != 0) {
            return false;
        }
        table = entry & kAddressMask;
    }
    std::uint64_t leaf = 0;
    if (!read_entry(table, indexes[3], leaf) || (leaf & kPresent) == 0) {
        return false;
    }
    return write_entry(table, indexes[3], 0);
}

bool X64PageTable::translate(
    std::uint64_t virtual_address,
    PageAccess access,
    std::uint64_t& physical_address) const {
    if (!valid_ || !canonical(virtual_address)) {
        return false;
    }
    const std::size_t indexes[] = {
        static_cast<std::size_t>((virtual_address >> 39U) & 0x1FFU),
        static_cast<std::size_t>((virtual_address >> 30U) & 0x1FFU),
        static_cast<std::size_t>((virtual_address >> 21U) & 0x1FFU),
        static_cast<std::size_t>((virtual_address >> 12U) & 0x1FFU)
    };
    std::uint64_t table = root_physical_;
    std::uint64_t leaf = 0;
    for (std::size_t level = 0; level < 4U; ++level) {
        if (!read_entry(table, indexes[level], leaf) || (leaf & kPresent) == 0) {
            return false;
        }
        if (level != 3U) {
            if ((leaf & (1ULL << 7U)) != 0) return false;
            table = leaf & kAddressMask;
        }
    }

    if (access == PageAccess::Write && (leaf & kWritable) == 0) return false;
    if (access == PageAccess::Execute && (leaf & kNoExecute) != 0) return false;

    physical_address = (leaf & kAddressMask) | (virtual_address & (kPageSize - 1U));
    return true;
}

}  // namespace anmu::ps5
