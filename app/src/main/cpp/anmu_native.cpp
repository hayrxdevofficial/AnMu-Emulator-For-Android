#include <jni.h>
#include <string>

namespace {
constexpr jint kBridgeVersion = 1;
constexpr const char* kRuntimeState = "QEMU_PACKAGED_PROCESS";
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_hayrx_anmu_NativeVmEngine_nativeBridgeVersion(
    JNIEnv* /* env */,
    jobject /* thiz */) {
    return kBridgeVersion;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_hayrx_anmu_NativeVmEngine_nativeRuntimeState(
    JNIEnv* env,
    jobject /* thiz */) {
    return env->NewStringUTF(kRuntimeState);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_hayrx_anmu_NativeVmEngine_nativeStartVm(
    JNIEnv* env,
    jobject /* thiz */,
    jstring /* iso_uri */,
    jint /* memory_mb */,
    jint /* cpu_count */,
    jstring /* disk_path */) {
    // QEMU is executed as a separate Android-native process by QemuVmController.
    // This legacy JNI method is intentionally not used to launch the VM.
    return env->NewStringUTF(
        "Use QemuVmController to launch the packaged QEMU process."
    );
}
