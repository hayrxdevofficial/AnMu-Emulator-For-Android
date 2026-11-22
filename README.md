# AnMu Emulator

A lightweight Android front end for booting ISO images in a QEMU-based virtual machine.

## Current milestone: 0.3.0 (QEMU ARM64 runtime build pipeline)

- Android 10+ support target (minSdk 29).
- Minimal emulator screen with a large guest-display area.
- ISO selection through Android's Storage Access Framework; the chosen document permission is persisted.
- Settings for guest RAM (1–6 GB), virtual disk size (20–30 GB), and CPU cores.
- Fixed keyboard and mouse controls at the bottom of the screen.
- No device-admin or root permission is requested.
- Android NDK / CMake native bridge builds for `arm64-v8a` and `x86_64`.
- CI now builds a pinned QEMU 11.1.2 Android ARM64 runtime using the Android NDK and packages its runtime libraries and firmware assets into the debug APK.

**Important:** QEMU is now built from source and included in CI-generated APKs for `arm64-v8a`, but the app does not yet launch the QEMU process or render its guest framebuffer. The JNI Start OS method still reports that VM startup is not implemented. No guest OS is claimed to be running. The current QEMU package is ARM64-only; x86_64 Android devices do not receive this runtime.

## QEMU build provenance

The Android ARM64 QEMU build recipe is pinned to commit `e3fc1befa3c135d28bf8d40e3c2264d93fea87ef` of [ExTV/Podroid](https://github.com/ExTV/Podroid). It builds QEMU 11.1.2 with Android NDK r27c and 16 KiB ELF page alignment. The build recipe includes Android-specific compatibility patches. QEMU is licensed under GPL-2.0; see the upstream [QEMU project](https://www.qemu.org) and the pinned build recipe for its build patches and dependency notices.

The CI workflow creates the `anmu-qemu-arm64` artifact, then packages the libraries and firmware into `anmu-debug-apk`. The QEMU artifact is retained for 14 days.

## Resource settings

The 6 GB RAM value is a requested maximum, not a guarantee. The app must calculate a safer per-device limit from available memory before any VM start. A virtual disk is intended to be sparse (grow as data is written), rather than immediately reserving all 20–30 GB.

## Why no administrator permission?

An ordinary Android application cannot grant itself extra CPU virtualization privileges by asking to be a device administrator. Device Admin is for device-management policies, not for QEMU acceleration. AnMu uses the Android document picker for ISO access and keeps its VM files in app storage.

## Build

Open this project in Android Studio with JDK 17 and Android SDK Platform 36 installed, then sync and build the `app` module.

The QEMU libraries are produced by the GitHub Actions workflow and are not committed as binary files. A local Android Studio build therefore builds the app shell and JNI bridge unless the matching QEMU runtime artifact has first been extracted into:

- `app/src/main/jniLibs/arm64-v8a/`
- `app/src/main/assets/qemu/`

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
2. **0.2.0** — NDK/CMake toolchain and JNI bridge scaffold for ARM64 and x86-64.
3. **0.3.0** — pinned QEMU 11.1.2 Android ARM64 source-build pipeline and APK packaging.
4. **0.4.0** — QEMU process lifecycle, ISO staging, virtual disk creation and guest framebuffer rendering.
5. **0.5.0** — keyboard/mouse event forwarding, safe memory limits and boot compatibility tests.

## Performance and compatibility notes

The current runtime uses QEMU TCG software CPU emulation; do not expect hardware-virtualization performance. The actual speed depends on the phone's CPU, RAM, Android build and guest architecture. The current QEMU build targets an AArch64 guest. x86/x86_64 guest support requires a separate QEMU target build and additional testing.

Useful platform documentation:
- [Android Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider)
- [Android file access and persistent URI permissions](https://developer.android.com/training/data-storage/shared/documents-files)
- [QEMU system emulation overview](https://www.qemu.org/docs/master/system/introduction.html)
