/*
 *  rapfi_jni.cpp - JNI wrapper for the embedded Rapfi engine.
 *
 *  Exposes the raw Rapfi command stream to the Java class
 *  com.gomocalc.app.engine.rapfi.RapfiJni. The engine runs in-process: the
 *  C++ side redirects std::cout/std::cerr to a line callback, which is
 *  marshalled back to RapfiJni.onOutputLine on the producing thread.
 *
 *  Copyright (C) 2024. Licensed under GPLv3 (see Copying.txt).
 */

#include <jni.h>

#include <mutex>
#include <string>

#include "../bridge/rapfi_bridge.h"

namespace {

JavaVM    *g_jvm          = nullptr;
jobject    g_engineObj    = nullptr;   // global reference to the RapfiJni instance
jmethodID  g_onOutputLine = nullptr;   // RapfiJni.onOutputLine(String)
std::mutex g_jniMutex;

/// Deliver one engine output line to Java. May be called from any native thread.
void outputCallback(const char *line, void *)
{
    if (!g_jvm || !g_engineObj || !g_onOutputLine)
        return;

    JNIEnv *env = nullptr;
    bool    attachedHere = false;

    if (g_jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK)
            return;
        attachedHere = true;
    }

    {
        std::lock_guard<std::mutex> lk(g_jniMutex);
        jstring jline = env->NewStringUTF(line);
        if (jline) {
            env->CallVoidMethod(g_engineObj, g_onOutputLine, jline);
            if (env->ExceptionCheck())
                env->ExceptionClear();
            env->DeleteLocalRef(jline);
        }
    }

    if (attachedHere)
        g_jvm->DetachCurrentThread();
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *)
{
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL
Java_com_gomocalc_app_engine_rapfi_RapfiJni_nativeInit(JNIEnv *env, jobject thiz, jstring configPath)
{
    // Cache a global reference to the RapfiJni instance and its callback method.
    if (g_engineObj) {
        env->DeleteGlobalRef(g_engineObj);
        g_engineObj = nullptr;
    }
    g_engineObj = env->NewGlobalRef(thiz);

    jclass cls    = env->GetObjectClass(thiz);
    g_onOutputLine = env->GetMethodID(cls, "onOutputLine", "(Ljava/lang/String;)V");
    env->DeleteLocalRef(cls);

    const char *cfg = configPath ? env->GetStringUTFChars(configPath, nullptr) : nullptr;
    int         ret = rapfi_init(cfg ? cfg : nullptr, outputCallback, nullptr);
    if (cfg)
        env->ReleaseStringUTFChars(configPath, cfg);

    if (ret != 0) {
        jclass ex = env->FindClass("java/lang/IllegalStateException");
        if (ex)
            env->ThrowNew(ex, "Rapfi engine failed to load config");
    }
    return ret;
}

JNIEXPORT void JNICALL
Java_com_gomocalc_app_engine_rapfi_RapfiJni_nativeSendCommand(JNIEnv *env, jobject, jstring cmd)
{
    const char *c = env->GetStringUTFChars(cmd, nullptr);
    if (c) {
        rapfi_send_command(c);
        env->ReleaseStringUTFChars(cmd, c);
    }
}

JNIEXPORT void JNICALL
Java_com_gomocalc_app_engine_rapfi_RapfiJni_nativeStopThinking(JNIEnv *, jobject)
{
    rapfi_stop_thinking();
}

JNIEXPORT jint JNICALL
Java_com_gomocalc_app_engine_rapfi_RapfiJni_nativeIsThinking(JNIEnv *, jobject)
{
    return rapfi_is_thinking();
}

JNIEXPORT void JNICALL
Java_com_gomocalc_app_engine_rapfi_RapfiJni_nativeTerminate(JNIEnv *env, jobject)
{
    rapfi_terminate();

    if (g_engineObj) {
        env->DeleteGlobalRef(g_engineObj);
        g_engineObj    = nullptr;
        g_onOutputLine = nullptr;
    }
}

}  // extern "C"
