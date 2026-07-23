#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

typedef struct TermuxBootstrap TermuxBootstrap;

extern TermuxBootstrap *termux_bootstrap_create(void);
extern int32_t termux_bootstrap_begin(TermuxBootstrap *handle, int32_t installed, int32_t prepare_succeeded);
extern int32_t termux_bootstrap_complete(TermuxBootstrap *handle, int32_t install_succeeded);
extern int32_t termux_bootstrap_state(const TermuxBootstrap *handle);
extern void termux_bootstrap_free(TermuxBootstrap *handle);

typedef struct TermuxTerminalSession TermuxTerminalSession;
extern TermuxTerminalSession *termux_terminal_session_create(const char *, const char *const *, size_t, size_t, size_t);
extern TermuxTerminalSession *termux_terminal_session_create_with_env(const char *, const char *const *, size_t, const char *const *, size_t, size_t, size_t);
extern size_t termux_runtime_environment(const char *, uint8_t *, size_t);
extern int32_t termux_terminal_session_feed_output(TermuxTerminalSession *, const uint8_t *, size_t);
extern int32_t termux_terminal_session_write_input(TermuxTerminalSession *, const uint8_t *, size_t);
extern int32_t termux_terminal_session_pump_output(TermuxTerminalSession *);
extern size_t termux_terminal_session_render(const TermuxTerminalSession *, uint8_t *, size_t);
extern int32_t termux_terminal_session_resize(TermuxTerminalSession *, size_t, size_t);
extern int32_t termux_terminal_session_try_wait(TermuxTerminalSession *, uint32_t *);
extern int32_t termux_terminal_session_terminate(TermuxTerminalSession *);
extern void termux_terminal_session_free(TermuxTerminalSession *);

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

JNIEXPORT jlong JNICALL Java_com_termux_rust_JniTerminalSessionBridge_nativeCreate(JNIEnv *env, jobject receiver, jstring command, jobjectArray arguments, jint columns, jint rows) {
    (void)receiver;
    if (command == NULL || columns <= 0 || rows <= 0) return 0;
    const char *program = (*env)->GetStringUTFChars(env, command, NULL);
    jsize count = arguments == NULL ? 0 : (*env)->GetArrayLength(env, arguments);
    const char **values = calloc((size_t)count, sizeof(*values));
    if (count > 0 && values == NULL) {
        if (program != NULL) (*env)->ReleaseStringUTFChars(env, command, program);
        return 0;
    }
    for (jsize index = 0; program != NULL && index < count; index++) {
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, arguments, index);
        values[index] = value == NULL ? NULL : (*env)->GetStringUTFChars(env, value, NULL);
    }
    TermuxTerminalSession *session = program == NULL ? NULL : termux_terminal_session_create(program, values, (size_t)count, (size_t)columns, (size_t)rows);
    for (jsize index = 0; index < count; index++) if (values[index] != NULL) { jstring value = (jstring)(*env)->GetObjectArrayElement(env, arguments, index); (*env)->ReleaseStringUTFChars(env, value, values[index]); }
    free(values);
    if (program != NULL) (*env)->ReleaseStringUTFChars(env, command, program);
    return (jlong)(intptr_t)session;
}

JNIEXPORT jlong JNICALL Java_com_termux_rust_JniTerminalSessionBridge_nativeCreateWithEnv(JNIEnv *env, jobject receiver, jstring command, jobjectArray arguments, jobjectArray environment, jint columns, jint rows) {
    (void)receiver;
    if (command == NULL || columns <= 0 || rows <= 0) return 0;
    const char *program = (*env)->GetStringUTFChars(env, command, NULL);
    jsize count = arguments == NULL ? 0 : (*env)->GetArrayLength(env, arguments);
    const char **values = calloc((size_t)count, sizeof(*values));
    jsize env_count = environment == NULL ? 0 : (*env)->GetArrayLength(env, environment);
    const char **env_values = calloc((size_t)env_count, sizeof(*env_values));
    if ((count > 0 && values == NULL) || (env_count > 0 && env_values == NULL)) {
        free(values);
        free(env_values);
        if (program != NULL) (*env)->ReleaseStringUTFChars(env, command, program);
        return 0;
    }
    for (jsize index = 0; program != NULL && index < count; index++) {
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, arguments, index);
        values[index] = value == NULL ? NULL : (*env)->GetStringUTFChars(env, value, NULL);
    }
    for (jsize index = 0; program != NULL && index < env_count; index++) {
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, environment, index);
        env_values[index] = value == NULL ? NULL : (*env)->GetStringUTFChars(env, value, NULL);
    }
    TermuxTerminalSession *session = program == NULL ? NULL : termux_terminal_session_create_with_env(program, values, (size_t)count, env_values, (size_t)env_count, (size_t)columns, (size_t)rows);
    for (jsize index = 0; index < count; index++) if (values[index] != NULL) { jstring value = (jstring)(*env)->GetObjectArrayElement(env, arguments, index); (*env)->ReleaseStringUTFChars(env, value, values[index]); }
    for (jsize index = 0; index < env_count; index++) if (env_values[index] != NULL) { jstring value = (jstring)(*env)->GetObjectArrayElement(env, environment, index); (*env)->ReleaseStringUTFChars(env, value, env_values[index]); }
    free(values);
    free(env_values);
    if (program != NULL) (*env)->ReleaseStringUTFChars(env, command, program);
    return (jlong)(intptr_t)session;
}

JNIEXPORT jint JNICALL Java_com_termux_rust_JniTerminalSessionBridge_nativeRuntimeEnvironmentSize(JNIEnv *env, jobject receiver, jstring packageName) {
    (void)receiver;
    if (packageName == NULL) return 0;
    const char *name = (*env)->GetStringUTFChars(env, packageName, NULL);
    jint size = name == NULL ? 0 : (jint)termux_runtime_environment(name, NULL, 0);
    if (name != NULL) (*env)->ReleaseStringUTFChars(env, packageName, name);
    return size;
}

JNIEXPORT jint JNICALL Java_com_termux_rust_JniTerminalSessionBridge_nativeRuntimeEnvironment(JNIEnv *env, jobject receiver, jstring packageName, jbyteArray output) {
    (void)receiver;
    if (packageName == NULL || output == NULL) return 0;
    const char *name = (*env)->GetStringUTFChars(env, packageName, NULL);
    jsize length = (*env)->GetArrayLength(env, output);
    jbyte *data = (*env)->GetByteArrayElements(env, output, NULL);
    jint written = 0;
    if (name != NULL && data != NULL) {
        written = (jint)termux_runtime_environment(name, (uint8_t *)data, (size_t)length);
    }
    if (data != NULL) (*env)->ReleaseByteArrayElements(env, output, data, 0);
    if (name != NULL) (*env)->ReleaseStringUTFChars(env, packageName, name);
    return written;
}

static jint session_bytes(JNIEnv *env, jlong handle, jbyteArray bytes, int input) {
    if (bytes == NULL) return -1;
    jsize length = (*env)->GetArrayLength(env, bytes); jbyte *data = (*env)->GetByteArrayElements(env, bytes, NULL);
    if (data == NULL) return -1; // pending OOM exception from GetByteArrayElements
    jint status = input ? termux_terminal_session_write_input((void *)(intptr_t)handle, (uint8_t *)data, (size_t)length) : termux_terminal_session_feed_output((void *)(intptr_t)handle, (uint8_t *)data, (size_t)length);
    (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT); return status;
}
JNIEXPORT jint JNICALL Java_com_termux_rust_JniTerminalSessionBridge_nativeFeedOutput(JNIEnv *e,jobject r,jlong h,jbyteArray b){(void)r;return session_bytes(e,h,b,0);} 
JNIEXPORT jint JNICALL Java_com_termux_rust_JniTerminalSessionBridge_nativeWriteInput(JNIEnv *e,jobject r,jlong h,jbyteArray b){(void)r;return session_bytes(e,h,b,1);} 
JNIEXPORT jint JNICALL Java_com_termux_rust_JniTerminalSessionBridge_nativePumpOutput(JNIEnv *e,jobject r,jlong h){(void)e;(void)r;return termux_terminal_session_pump_output((void *)(intptr_t)h);} 
JNIEXPORT jint JNICALL Java_com_termux_rust_JniTerminalSessionBridge_nativeRenderSize(JNIEnv *e,jobject r,jlong h){(void)e;(void)r;return (jint)termux_terminal_session_render((void *)(intptr_t)h,NULL,0);} 
JNIEXPORT jint JNICALL Java_com_termux_rust_JniTerminalSessionBridge_nativeRender(JNIEnv *e,jobject r,jlong h,jbyteArray b){(void)r;jsize n=(*e)->GetArrayLength(e,b);jbyte *d=(*e)->GetByteArrayElements(e,b,NULL);if(d==NULL)return 0;size_t w=termux_terminal_session_render((void *)(intptr_t)h,(uint8_t *)d,(size_t)n);(*e)->ReleaseByteArrayElements(e,b,d,0);return (jint)w;}
JNIEXPORT jint JNICALL Java_com_termux_rust_JniTerminalSessionBridge_nativeResize(JNIEnv *e,jobject r,jlong h,jint c,jint rows){(void)e;(void)r;return termux_terminal_session_resize((void *)(intptr_t)h,c,rows);} 
JNIEXPORT jint JNICALL Java_com_termux_rust_JniTerminalSessionBridge_nativeTryWait(JNIEnv *e,jobject r,jlong h,jintArray out){(void)r;uint32_t code=0;jint status=termux_terminal_session_try_wait((void *)(intptr_t)h,&code);if(status==0&&out!=NULL&&(*e)->GetArrayLength(e,out)>=1){jint value=(jint)code;(*e)->SetIntArrayRegion(e,out,0,1,&value);}return status;} 
JNIEXPORT jint JNICALL Java_com_termux_rust_JniTerminalSessionBridge_nativeTerminate(JNIEnv *e,jobject r,jlong h){(void)e;(void)r;return termux_terminal_session_terminate((void *)(intptr_t)h);} 
JNIEXPORT void JNICALL Java_com_termux_rust_JniTerminalSessionBridge_nativeFree(JNIEnv *e,jobject r,jlong h){(void)e;(void)r;termux_terminal_session_free((void *)(intptr_t)h);} 
