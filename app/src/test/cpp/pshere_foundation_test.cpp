#include "ps5/input/controller_state.h"
#include "ps5/memory/guest_memory.h"

#include <cassert>
#include <cmath>
#include <cstdint>
#include <limits>

using anmu::ps5::GuestMemory;
using anmu::ps5::MemoryPermission;
using anmu::ps5::input::Button;
using anmu::ps5::input::ControllerSnapshot;
using anmu::ps5::input::ControllerState;

int main() {
    GuestMemory memory(1024);
    assert(memory.map(0x1000, 256, MemoryPermission::Read | MemoryPermission::Write));
    assert(!memory.map(0x10F0, 32, MemoryPermission::Read));
    const std::uint32_t source = 0xA1B2C3D4U;
    std::uint32_t destination = 0;
    assert(memory.write(0x1010, &source, sizeof(source)));
    assert(memory.read(0x1010, &destination, sizeof(destination)));
    assert(destination == source);
    assert(!memory.read(0x10FF, &destination, sizeof(destination)));
    assert(memory.unmap(0x1000));
    assert(memory.mapped_region_count() == 0);

    ControllerState controller;
    ControllerSnapshot event;
    event.device_id = 42;
    event.connected = true;
    event.buttons = anmu::ps5::input::button_mask(Button::Cross);
    event.left_x = 1.5F;
    event.left_y = -1.5F;
    event.right_trigger = 0.75F;
    controller.update(event);

    auto state = controller.snapshot();
    assert(state.connected && state.device_id == 42);
    assert(state.buttons == anmu::ps5::input::button_mask(Button::Cross));
    assert(state.left_x == 1.0F && state.left_y == -1.0F);
    assert(std::fabs(state.right_trigger - 0.75F) < 0.001F);

    controller.disconnect(7);
    assert(controller.snapshot().connected);
    controller.disconnect(42);
    state = controller.snapshot();
    assert(!state.connected && state.device_id == -1 && state.buttons == 0);
    assert(state.left_x == 0.0F && state.right_trigger == 0.0F);

    event.device_id = 43;
    event.connected = true;
    event.left_x = std::numeric_limits<float>::quiet_NaN();
    controller.update(event);
    assert(controller.snapshot().left_x == 0.0F);
    return 0;
}
