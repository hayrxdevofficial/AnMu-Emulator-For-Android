package dev.hayrx.anmu

/**
 * JNI boundary for the QEMU runtime status bridge. QEMU itself is launched by QemuVmController as a separate native process.
 *
 * The native library currently verifies that the Android NDK/JNI toolchain is wired correctly.
 * It does not launch the VM; QemuVmController owns process startup and VNC.
 */
class NativeVmEngine {
    companion object {
        const val STATE_QEMU_PACKAGED_PROCESS = "QEMU_PACKAGED_PROCESS"

        init {
            System.loadLibrary("anmu_native")
        }
    }

    external fun nativeBridgeVersion(): Int
    external fun nativeRuntimeState(): String

    /**
     * Legacy compatibility entry point. QemuVmController owns real QEMU process startup and shutdown.
     */
    external fun nativeStartVm(
        isoUri: String,
        memoryMb: Int,
        cpuCount: Int,
        diskPath: String
    ): String
}
