#include "guest_memory.h"

#include <algorithm>
#include <cstring>
#include <limits>
#include <new>

namespace anmu::ps5 {
namespace {

bool range_end(std::uint64_t base, std::size_t size, std::uint64_t& end) noexcept {
    if (size == 0 || size > std::numeric_limits<std::uint64_t>::max() - base) {
        return false;
    }
    end = base + static_cast<std::uint64_t>(size);
    return true;
}

}  // namespace

GuestMemory::GuestMemory(std::size_t max_region_bytes)
    : max_region_bytes_(max_region_bytes) {}

bool GuestMemory::map(
    std::uint64_t guest_base,
    std::size_t size,
    MemoryPermission permissions) {
    if (size == 0 || size > max_region_bytes_) {
        return false;
    }

    std::uint64_t new_end = 0;
    if (!range_end(guest_base, size, new_end)) {
        return false;
    }

    std::unique_lock<std::shared_mutex> lock(mutex_);
    auto position = std::lower_bound(
        regions_.begin(), regions_.end(), guest_base,
        [](const Region& region, std::uint64_t address) {
            return region.base < address;
        });

    if (position != regions_.begin()) {
        const auto& previous = *std::prev(position);
        std::uint64_t previous_end = 0;
        if (!range_end(previous.base, previous.bytes.size(), previous_end) ||
            previous_end > guest_base) {
            return false;
        }
    }
    if (position != regions_.end() && new_end > position->base) {
        return false;
    }

    try {
        Region region{guest_base, std::vector<std::uint8_t>(size, 0), permissions};
        regions_.insert(position, std::move(region));
    } catch (const std::bad_alloc&) {
        return false;
    }
    return true;
}

bool GuestMemory::unmap(std::uint64_t guest_base) {
    std::unique_lock<std::shared_mutex> lock(mutex_);
    const auto position = std::lower_bound(
        regions_.begin(), regions_.end(), guest_base,
        [](const Region& region, std::uint64_t address) {
            return region.base < address;
        });
    if (position == regions_.end() || position->base != guest_base) {
        return false;
    }
    regions_.erase(position);
    return true;
}

bool GuestMemory::read(
    std::uint64_t guest_address,
    void* destination,
    std::size_t size) const {
    if (destination == nullptr || size == 0) {
        return false;
    }

    std::uint64_t access_end = 0;
    if (!range_end(guest_address, size, access_end)) {
        return false;
    }

    std::shared_lock<std::shared_mutex> lock(mutex_);
    const auto position = find_region_locked(guest_address);
    if (position == regions_.end() ||
        !has_permission(position->permissions, MemoryPermission::Read)) {
        return false;
    }

    std::uint64_t region_end = 0;
    if (!range_end(position->base, position->bytes.size(), region_end) ||
        access_end > region_end) {
        return false;
    }

    const auto offset = static_cast<std::size_t>(guest_address - position->base);
    std::memcpy(destination, position->bytes.data() + offset, size);
    return true;
}

bool GuestMemory::write(
    std::uint64_t guest_address,
    const void* source,
    std::size_t size) {
    if (source == nullptr || size == 0) {
        return false;
    }

    std::uint64_t access_end = 0;
    if (!range_end(guest_address, size, access_end)) {
        return false;
    }

    std::unique_lock<std::shared_mutex> lock(mutex_);
    const auto position = find_region_locked(guest_address);
    if (position == regions_.end() ||
        !has_permission(position->permissions, MemoryPermission::Write)) {
        return false;
    }

    std::uint64_t region_end = 0;
    if (!range_end(position->base, position->bytes.size(), region_end) ||
        access_end > region_end) {
        return false;
    }

    const auto offset = static_cast<std::size_t>(guest_address - position->base);
    std::memcpy(position->bytes.data() + offset, source, size);
    return true;
}

std::size_t GuestMemory::mapped_region_count() const {
    std::shared_lock<std::shared_mutex> lock(mutex_);
    return regions_.size();
}

GuestMemory::RegionList::const_iterator GuestMemory::find_region_locked(
    std::uint64_t address) const {
    auto position = std::upper_bound(
        regions_.begin(), regions_.end(), address,
        [](std::uint64_t value, const Region& region) {
            return value < region.base;
        });
    if (position == regions_.begin()) {
        return regions_.end();
    }
    --position;

    std::uint64_t region_end = 0;
    if (!range_end(position->base, position->bytes.size(), region_end) ||
        address >= region_end) {
        return regions_.end();
    }
    return position;
}

GuestMemory::RegionList::iterator GuestMemory::find_region_locked(std::uint64_t address) {
    auto position = std::upper_bound(
        regions_.begin(), regions_.end(), address,
        [](std::uint64_t value, const Region& region) {
            return value < region.base;
        });
    if (position == regions_.begin()) {
        return regions_.end();
    }
    --position;

    std::uint64_t region_end = 0;
    if (!range_end(position->base, position->bytes.size(), region_end) ||
        address >= region_end) {
        return regions_.end();
    }
    return position;
}

}  // namespace anmu::ps5
