#include <jni.h>
#include <stdint.h>

typedef struct TermuxBootstrap TermuxBootstrap;

extern TermuxBootstrap *termux_bootstrap_create(void);
extern int32_t termux_bootstrap_begin(TermuxBootstrap *handle, int32_t installed, int32_t prepare_succeeded);
extern int32_t termux_bootstrap_complete(TermuxBootstrap *handle, int32_t install_succeeded);
extern int32_t termux_bootstrap_state(const TermuxBootstrap *handle);
extern void termux_bootstrap_free(TermuxBootstrap *handle);

static TermuxBootstrap *bootstrap_from_handle(jlong handle) {
    return (TermuxBootstrap *)(intptr_t)handle;
}

JNIEXPORT jlong JNICALL
Java_com_termux_rust_JniBootstrapBridge_nativeCreate(JNIEnv *environment, jobject receiver) {
    (void)environment;
    (void)receiver;
    return (jlong)(intptr_t)termux_bootstrap_create();
}

JNIEXPORT jint JNICALL
Java_com_termux_rust_JniBootstrapBridge_nativeBegin(
    JNIEnv *environment,
    jobject receiver,
    jlong handle,
    jboolean installed,
    jboolean prepare_succeeded
) {
    (void)environment;
    (void)receiver;
    return termux_bootstrap_begin(
        bootstrap_from_handle(handle),
        installed == JNI_TRUE,
        prepare_succeeded == JNI_TRUE
    );
}

JNIEXPORT jint JNICALL
Java_com_termux_rust_JniBootstrapBridge_nativeComplete(
    JNIEnv *environment,
    jobject receiver,
    jlong handle,
    jboolean install_succeeded
) {
    (void)environment;
    (void)receiver;
    return termux_bootstrap_complete(bootstrap_from_handle(handle), install_succeeded == JNI_TRUE);
}

JNIEXPORT jint JNICALL
Java_com_termux_rust_JniBootstrapBridge_nativeState(JNIEnv *environment, jobject receiver, jlong handle) {
    (void)environment;
    (void)receiver;
    return termux_bootstrap_state(bootstrap_from_handle(handle));
}

JNIEXPORT void JNICALL
Java_com_termux_rust_JniBootstrapBridge_nativeFree(JNIEnv *environment, jobject receiver, jlong handle) {
    (void)environment;
    (void)receiver;
    termux_bootstrap_free(bootstrap_from_handle(handle));
}
