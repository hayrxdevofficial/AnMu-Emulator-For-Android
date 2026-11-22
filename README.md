# AnMu Emulator

An Android application that boots guest operating systems from ISO files using QEMU system emulation.

## Current milestone: 0.4.0 (QEMU process, framebuffer and input integration)

- Android 10+ host target (minSdk 29).
- Minimal interface: Settings and Select ISO before an image is chosen; guest display, keyboard and touchpad after selection.
- ISO is copied from Android's Storage Access Framework into app-private storage before launch.
- Configurable guest RAM (1–6 GB), sparse virtual disk (20–30 GB), CPU cores and guest architecture.
- QEMU 11.1.2 is built for an ARM64 Android host with AArch64 and x86_64 guest machine targets.
- TCG software CPU emulation; no root, Device Admin or KVM permission is requested.
- QEMU is launched as an app-owned native process. A loopback-only VNC server supplies the guest framebuffer.
- Touch input, pointer movement, physical keyboard events and typed text are forwarded through RFB/VNC.
- QEMU stderr and serial output are kept in app-private files for diagnostics.

## Important compatibility limits

- **Host device:** this APK is built only for Android devices with an `arm64-v8a` CPU. x86_64 Android host devices are not supported.
- **Guest architecture:** separate ARM64-host QEMU executables provide AArch64 and x86_64 guest machine types. x86_64 guest emulation on an ARM phone uses TCG and can be slow. AArch64 guests require the bundled EDK2 `QEMU_EFI.fd` firmware.
- **ISO compatibility:** not every ISO is bootable. The ISO must contain boot media for the selected guest architecture and firmware mode. Select x86_64 for typical PC/Linux desktop ISOs and AArch64 for ARM64 images.
- **Performance:** TCG is software emulation, not hardware virtualization. Speed depends on the host CPU, available RAM, thermals, guest OS and its drivers. The UI does not claim KVM acceleration.
- **Memory:** AnMu checks available memory before startup and reduces the requested guest RAM to leave memory for Android. Startup is refused if less than 1 GB can safely be allocated.
- **Storage:** the virtual disk is a sparse raw image. Its configured maximum size does not mean all of that storage is immediately filled, but the host filesystem still needs room as the guest writes data.
- **Display/input:** the current VNC client negotiates raw framebuffer updates. High resolutions and rapid full-screen updates can use substantial CPU and memory.
- **Lifecycle:** QEMU runs while the app process remains alive. Force-stopping the app terminates the child QEMU process. A dedicated foreground-service mode is not yet implemented.

## QEMU build provenance

The Android ARM64 QEMU build recipe is pinned to commit `e3fc1befa3c135d28bf8d40e3c2264d93fea87ef` of [ExTV/Podroid](https://github.com/ExTV/Podroid), with AnMu's CI applying the dual-guest and VNC build options. QEMU 11.1.2 is cross-compiled with Android NDK r27c. The workflow also builds the AArch64 EDK2 firmware from [Tianocore EDK2](https://github.com/tianocore/edk2).

QEMU is licensed under GPL-2.0. The exact upstream sources and Android-specific build recipe are linked above; dependency and firmware license notices must be retained with redistributed builds.

## Build

Open the project in Android Studio with JDK 17 and Android SDK Platform 36 installed.

The QEMU runtime is built by GitHub Actions and is not committed as binary files. The workflow:
1. Builds QEMU and the ARM64 UEFI firmware.
2. Packages QEMU executables, required shared libraries and firmware assets.
3. Builds the Android debug APK.

A local Android Studio build without the downloaded runtime artifact builds only the app shell/JNI library and cannot launch a VM. The runtime files must first be extracted into:
- `app/src/main/jniLibs/arm64-v8a/`
- `app/src/main/assets/qemu/`

The Gradle wrapper is not committed yet; use Gradle 8.13:

```bash
gradle wrapper --gradle-version 8.13
./gradlew assembleDebug
```

## Current CI artifact

The latest successful QEMU/APK build is available in [GitHub Actions](https://github.com/hayrxdevofficial/AnMu-Emulator-For-Android/actions). The APK artifact is named `anmu-debug-apk`.

## Project notes

- Android Device Admin is not a CPU virtualization permission and is not requested.
- ISO access uses Android's document picker and persisted URI grants.
- QEMU's VNC listener binds to loopback only; it is not exposed to the local network.
- QEMU uses user-mode networking for guest outbound connectivity.
