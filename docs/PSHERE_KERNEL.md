# PSHere — Android ARM64 kernel foundation

PSHere is the working name for AnMu's experimental, host-independent console
compatibility core. It is a research project, not a Linux kernel replacement
and not yet a PS5-compatible kernel.

## Initial host target

- Samsung Galaxy Tab S8+ class device.
- Android ARM64 / Snapdragon 8 Gen 1 / 12 GB RAM.
- Native implementation: C++17 through the Android NDK.
- Kotlin remains the Android integration layer for lifecycle, permissions,
  controller discovery, and UI.

## Current native foundations

- `ps5/memory`: checked sparse guest-memory mappings with bounded allocations.
- `ps5/input`: normalized controller snapshot and thread-safe state mailbox.
- The controller API is platform-neutral. It does not yet read Android
  InputDevice events or expose a real DualSense guest device.
- Existing QEMU/VNC input remains a separate keyboard/pointer path.

## Planned kernel-facing interfaces

1. Guest address-space and page permission model.
2. CPU reference interpreter and exception model.
3. Thread scheduler, synchronization primitives, and handle table.
4. A deliberately small, documented syscall compatibility layer.
5. Graphics, audio, and network interfaces with explicit host capability checks.

## Safety and compatibility boundaries

PSHere will not bypass firmware authentication, DRM, secure boot, or account
security. It will not collect PSN credentials or pretend to provide PSN login.
Firmware selection is metadata/input management only until a real, lawful,
well-understood boot path exists. No commercial game compatibility is claimed.

## Device resource policy

All large guest allocations must be bounded. Runtime budgets must account for
Android and the UI, not merely physical RAM. CPU/GPU work must be throttled or
stopped when thermal or memory conditions become unsafe.
