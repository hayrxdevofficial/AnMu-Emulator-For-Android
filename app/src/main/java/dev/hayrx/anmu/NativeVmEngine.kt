package dev.hayrx.anmu

/**
 * JNI boundary for the future QEMU runtime.
 *
 * The native library currently verifies that the Android NDK/JNI toolchain is wired correctly.
 * It deliberately refuses to claim that a guest has booted until QEMU is linked.
 */
class NativeVmEngine {
    companion object {
        const val STATE_QEMU_NOT_LINKED = "QEMU_NOT_LINKED"

        init {
            System.loadLibrary("anmu_native")
        }
    }

    external fun nativeBridgeVersion(): Int
    external fun nativeRuntimeState(): String

    /**
     * Returns a diagnostic message. In this milestone it always reports that QEMU is absent.
     */
    external fun nativeStartVm(
        isoUri: String,
        memoryMb: Int,
        cpuCount: Int,
        diskPath: String
    ): String
}
