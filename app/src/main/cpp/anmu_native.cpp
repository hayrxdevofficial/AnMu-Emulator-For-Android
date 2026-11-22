#include <jni.h>
#include <string>

namespace {
constexpr jint kBridgeVersion = 1;
constexpr const char* kRuntimeState = "QEMU_NOT_LINKED";
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
    // This is an explicit integration boundary, not a simulated VM boot.
    // Replace this response only after the QEMU Android runtime is linked.
    return env->NewStringUTF(
        "QEMU runtime is not bundled. No virtual machine was started."
    );
}
