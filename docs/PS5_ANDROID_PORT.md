# AnMu PS5 Android Port — Engineering Plan

Status: architecture foundation only. This document does not claim that AnMu currently boots PS5 firmware or runs PS5 games.

## Target device and host

- Android ARM64 (the initial target is the Galaxy Tab S8+ class of device).
- Android NDK / C++17 for native components; Kotlin remains responsible for Android lifecycle, permissions, UI, and device input.
- Vulkan is the intended graphics API, but required features and driver behavior must be queried at runtime. Do not assume desktop Vulkan capabilities.
- Thermal and memory budgets are first-class constraints. The emulator must be able to stop safely, reduce workload, and preserve Android responsiveness.

## Existing AnMu components

The current application contains a QEMU process launcher, a loopback VNC client, and libslirp user-mode networking. These are useful for ordinary virtual machines and development tests. They are not a PS5 compatibility layer: QEMU's generic AArch64/x86_64 machines do not model the PS5 (Prospero) CPU platform, kernel ABI, GPU command processor, or firmware boot chain.

The current VNC input path sends keyboard and pointer events. Its gamepad mapping is a keyboard fallback, not a native DualSense/PS5 controller implementation. Keep these paths separate from the future PS5 input backend.

## Proposed native module boundaries

All modules must have explicit interfaces and independently testable contracts.

- `ps5/common`: fixed-width types, checked arithmetic, logging, result/error types.
- `ps5/memory`: guest virtual address space, page permissions, mappings, and bounds checks.
- `ps5/cpu`: guest instruction decoding, interpreter/reference implementation, and later an ARM64 JIT backend. Never execute guest bytes as host code.
- `ps5/kernel`: user-mode process model, threads, synchronization primitives, handles, and a documented compatibility syscall layer.
- `ps5/graphics`: guest GPU resource model and command validation, followed by a Vulkan backend after feature detection.
- `ps5/audio`: guest audio queue model and Android output integration.
- `ps5/input`: normalized controller events (buttons, D-pad, both sticks, triggers), hot-plug, calibration, and optional haptics when the host API/device supports them.
- `ps5/network`: isolated guest networking with explicit permissions and test endpoints. This does not imply PSN support.
- `ps5/firmware`: read-only metadata inspection and compatibility reporting. Selecting a PUP is not equivalent to booting it.

## Development milestones

### M0 — Android host foundation
- Establish the NDK build target and module boundaries.
- Add capability reporting for CPU ABI, Vulkan version/extensions, memory, and thermal status.
- Normalize Android game-controller events and test hot-plug / disconnect cleanup.
- Preserve the existing QEMU PC/ARM VM path unchanged.

### M1 — CPU and memory research prototype
- Implement checked guest memory primitives and unit tests.
- Build an instruction decoder/interpreter test harness using small, legally created test programs.
- Validate deterministic register, flag, branch, and memory behavior before any JIT work.

### M2 — Minimal executable environment
- Define the executable/container loader only from public, documented formats and user-provided test binaries.
- Implement a small, testable syscall subset for a controlled homebrew test environment.
- Do not claim commercial-game compatibility.

### M3 — Graphics and audio prototypes
- Query Vulkan capabilities on the actual Android device.
- Validate a minimal host-rendered test triangle and resource lifetime behavior.
- Add audio output tests and thermal/performance instrumentation.

### M4 — PS5 compatibility research
- Study public documentation and open-source implementations.
- Implement only well-understood, testable compatibility behavior.
- Firmware authentication, platform security, proprietary account services, and PSN are not assumed or bypassed.

## Acceptance gates

A milestone is complete only when:
1. The Android ARM64 build succeeds in CI.
2. Unit tests pass on the host where possible.
3. Device-specific checks report unsupported features rather than crashing.
4. No firmware or game is reported as booted unless an actual guest execution trace and rendered output prove it.
5. Thermal and memory guardrails are exercised on the target tablet.

## Explicit non-goals for the foundation

- No fake PS5 dashboard presented as emulation.
- No promise that a PUP file can boot in QEMU.
- No credential collection, PSN login imitation, authentication bypass, or extraction of console secrets.
- No downloading or bundling proprietary Sony firmware or games.
