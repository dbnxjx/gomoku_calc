/*
 *  rapfi_jni.cpp - JNI wrapper exposing the Rapfi engine bridge to Android
 *  (Kotlin/Java) code.
 *
 *  It mirrors the web API (src/ai/engine.js):
 *      init(callback)      -> RapfiEngine.nativeInit
 *      sendCommand(cmd)    -> RapfiEngine.nativeSendCommand
 *      stopThinking()      -> RapfiEngine.nativeStopThinking
 *
 *  Engine output lines are marshalled back to the RapfiEngine.onOutputLine
 *  method on the thread that produced them.
 *
 *  Copyright (C) 2024. Licensed under GPLv3 (see Copying.txt).
 */

#include <jni.h>

#include <mutex>
#include <string>

#include "../bridge/rapfi_bridge.h"

namespace {

JavaVM    *g_jvm           = nullptr;
jobject    g_engineObj     = nullptr;  // global reference to RapfiEngine
jmethodID  g_onOutputLine  = nullptr;  // RapfiEngine.onOutputLine(String)
std::mutex g_jniMutex;

/// Deliver one output line to Kotlin. May be called from any native thread.
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

JNIEXPORT void JNICALL
Java_com_gomoku_rapfi_RapfiEngine_nativeInit(JNIEnv *env, jobject thiz, jstring configPath)
{
    // Cache a global reference to the engine object and its callback method.
    if (g_engineObj) {
        env->DeleteGlobalRef(g_engineObj);
        g_engineObj = nullptr;
    }
    g_engineObj = env->NewGlobalRef(thiz);

    jclass cls  = env->GetObjectClass(thiz);
    g_onOutputLine = env->GetMethodID(cls, "onOutputLine", "(Ljava/lang/String;)V");
    env->DeleteLocalRef(cls);

    const char *cfg = configPath ? env->GetStringUTFChars(configPath, nullptr) : nullptr;
    int         ret = rapfi_init(cfg ? cfg : nullptr, outputCallback, nullptr);
    if (cfg)
        env->ReleaseStringUTFChars(configPath, cfg);

    // Propagate init failure as a Java exception.
    if (ret != 0) {
        jclass ex = env->FindClass("java/lang/RuntimeException");
        if (ex)
            env->ThrowNew(ex, "Rapfi engine failed to load config");
    }
}

JNIEXPORT void JNICALL
Java_com_gomoku_rapfi_RapfiEngine_nativeSendCommand(JNIEnv *env, jobject, jstring cmd)
{
    const char *c = env->GetStringUTFChars(cmd, nullptr);
    if (c) {
        rapfi_send_command(c);
        env->ReleaseStringUTFChars(cmd, c);
    }
}

JNIEXPORT void JNICALL
Java_com_gomoku_rapfi_RapfiEngine_nativeStopThinking(JNIEnv *, jobject)
{
    rapfi_stop_thinking();
}

JNIEXPORT jint JNICALL
Java_com_gomoku_rapfi_RapfiEngine_nativeIsThinking(JNIEnv *, jobject)
{
    return rapfi_is_thinking();
}

JNIEXPORT void JNICALL
Java_com_gomoku_rapfi_RapfiEngine_nativeTerminate(JNIEnv *env, jobject)
{
    rapfi_terminate();

    if (g_engineObj) {
        env->DeleteGlobalRef(g_engineObj);
        g_engineObj    = nullptr;
        g_onOutputLine = nullptr;
    }
}

}  // extern "C"
