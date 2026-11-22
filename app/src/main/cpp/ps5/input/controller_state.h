#pragma once

#include <cstdint>
#include <mutex>

namespace anmu::ps5::input {

// Host-independent controller representation. Android InputDevice / USB / Bluetooth
// adapters translate platform events into this structure.
enum class Button : std::uint32_t {
    Cross      = 1U << 0U,
    Circle     = 1U << 1U,
    Square     = 1U << 2U,
    Triangle   = 1U << 3U,
    L1         = 1U << 4U,
    R1         = 1U << 5U,
    L3         = 1U << 6U,
    R3         = 1U << 7U,
    Options    = 1U << 8U,
    Create     = 1U << 9U,
    System     = 1U << 10U,
    Touchpad   = 1U << 11U,
};

constexpr std::uint32_t button_mask(Button button) noexcept {
    return static_cast<std::uint32_t>(button);
}

enum class Dpad : std::uint8_t {
    Center = 0,
    Up,
    UpRight,
    Right,
    DownRight,
    Down,
    DownLeft,
    Left,
    UpLeft,
};

struct ControllerSnapshot final {
    std::int32_t device_id = -1;
    bool connected = false;
    std::uint32_t buttons = 0;
    Dpad dpad = Dpad::Center;
    float left_x = 0.0F;
    float left_y = 0.0F;
    float right_x = 0.0F;
    float right_y = 0.0F;
    float left_trigger = 0.0F;
    float right_trigger = 0.0F;
};

// Thread-safe latest-state mailbox. A platform adapter can update it from Android
// input callbacks; the emulation thread reads consistent snapshots.
class ControllerState final {
public:
    void update(const ControllerSnapshot& state);
    void disconnect(std::int32_t device_id);
    ControllerSnapshot snapshot() const;

private:
    static ControllerSnapshot sanitize(const ControllerSnapshot& state) noexcept;

    mutable std::mutex mutex_;
    ControllerSnapshot state_{};
};

}  // namespace anmu::ps5::input
