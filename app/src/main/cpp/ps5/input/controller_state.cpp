#include "controller_state.h"

#include <algorithm>
#include <cmath>

namespace anmu::ps5::input {
namespace {

float clamp_axis(float value, float minimum, float maximum) noexcept {
    if (!std::isfinite(value)) {
        return 0.0F;
    }
    return std::max(minimum, std::min(value, maximum));
}

bool valid_dpad(Dpad value) noexcept {
    return static_cast<std::uint8_t>(value) <= static_cast<std::uint8_t>(Dpad::UpLeft);
}

}  // namespace

ControllerSnapshot ControllerState::sanitize(const ControllerSnapshot& state) noexcept {
    ControllerSnapshot clean = state;
    clean.left_x = clamp_axis(state.left_x, -1.0F, 1.0F);
    clean.left_y = clamp_axis(state.left_y, -1.0F, 1.0F);
    clean.right_x = clamp_axis(state.right_x, -1.0F, 1.0F);
    clean.right_y = clamp_axis(state.right_y, -1.0F, 1.0F);
    clean.left_trigger = clamp_axis(state.left_trigger, 0.0F, 1.0F);
    clean.right_trigger = clamp_axis(state.right_trigger, 0.0F, 1.0F);
    if (!valid_dpad(clean.dpad)) {
        clean.dpad = Dpad::Center;
    }
    if (!clean.connected) {
        clean.device_id = -1;
        clean.buttons = 0;
        clean.dpad = Dpad::Center;
        clean.left_x = 0.0F;
        clean.left_y = 0.0F;
        clean.right_x = 0.0F;
        clean.right_y = 0.0F;
        clean.left_trigger = 0.0F;
        clean.right_trigger = 0.0F;
    }
    return clean;
}

void ControllerState::update(const ControllerSnapshot& state) {
    const ControllerSnapshot clean = sanitize(state);
    std::lock_guard<std::mutex> lock(mutex_);
    state_ = clean;
}

void ControllerState::disconnect(std::int32_t device_id) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (state_.connected && state_.device_id == device_id) {
        state_ = ControllerSnapshot{};
    }
}

ControllerSnapshot ControllerState::snapshot() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return state_;
}

}  // namespace anmu::ps5::input
