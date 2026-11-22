# AnMu Emulator

A lightweight Android front end for booting ISO images in a QEMU-based virtual machine.

## Current milestone: 0.2.0 (JNI / NDK bridge foundation)

- Android 10+ support target (minSdk 29).
- Minimal emulator screen with a large guest-display area.
- ISO selection through Android's Storage Access Framework; the chosen document permission is persisted.
- Settings for guest RAM (512 MB–6 GB), virtual disk size (20–30 GB), and CPU cores.
- Fixed keyboard and mouse controls at the bottom of the screen.
- No device-admin or root permission is requested.
- Android NDK / CMake native library builds for `arm64-v8a` and `x86_64`.
- Kotlin-to-C++ JNI bridge is connected to the Start OS action.

**Important:** this milestone contains a working JNI/NDK bridge scaffold, not the QEMU runtime itself. The native bridge intentionally returns `QEMU runtime is not bundled`; no guest OS is booted yet. The app must not display a fake running state.

## Resource settings

The 6 GB RAM value is a requested maximum, not a guarantee. The app calculates a safer per-device limit from available memory before any future VM start. A virtual disk is intended to be sparse (grow as data is written), rather than immediately reserving all 20–30 GB.

## Why no administrator permission?

An ordinary Android application cannot grant itself extra CPU virtualization privileges by asking to be a device administrator. Device Admin is for device-management policies, not for QEMU acceleration. AnMu uses the Android document picker for ISO access and keeps its VM files in app storage.

## Build

Open this project in Android Studio with JDK 17 and Android SDK Platform 36 installed, then sync and build the `app` module.

The Gradle wrapper is not committed yet; use an installed Gradle 8.13 to generate it with:

```bash
gradle wrapper --gradle-version 8.13
```

Then build:

```bash
./gradlew assembleDebug
```

## Planned milestones

1. **0.1.0** — app shell, ISO picker, persistent settings and input-control layout.
2. **0.2.0** — NDK/CMake toolchain and JNI bridge scaffold for ARM64 and x86-64. QEMU itself is not linked yet.
3. **0.3.0** — integrate a pinned QEMU source/build, define the VM lifecycle and implement virtual disk creation.
4. **0.4.0** — guest display rendering, keyboard/mouse event forwarding, safe memory limits and compatibility tests.

## Performance and compatibility notes

QEMU can emulate different CPU architectures, but software CPU emulation (TCG) is slower than hardware-assisted virtualization. The actual speed depends on the phone's CPU, RAM, Android build, guest architecture and whether a supported accelerator is available. AnMu will report unsupported acceleration rather than claiming that every phone can run every ISO smoothly.

Useful platform documentation:
- [Android Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider)
- [Android file access and persistent URI permissions](https://developer.android.com/training/data-storage/shared/documents-files)
- [QEMU system emulation overview](https://www.qemu.org/docs/master/system/introduction.html)
