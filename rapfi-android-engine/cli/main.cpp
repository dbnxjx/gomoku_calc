/*
 *  rapfi-cli - a native command line test harness for the Rapfi engine bridge.
 *
 *  It exercises the exact same C API that the Android JNI layer uses, making it
 *  possible to verify engine behaviour on a desktop Linux machine without an
 *  Android device/NDK.
 *
 *  Usage:
 *      rapfi-cli [--config <path>] < commands.txt
 *
 *  Every engine output line is printed to stdout prefixed with "OUT " so that
 *  it can be distinguished from harness diagnostics (which use "## ").
 *
 *  Copyright (C) 2024. Licensed under GPLv3 (see Copying.txt).
 */

#include "../engine/bridge/rapfi_bridge.h"

#include <chrono>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>

namespace {

std::mutex g_printMutex;

void printOutput(const char *line, void *)
{
    std::lock_guard<std::mutex> lk(g_printMutex);
    std::printf("OUT %s\n", line);
    std::fflush(stdout);
}

void printDiag(const std::string &msg)
{
    std::lock_guard<std::mutex> lk(g_printMutex);
    std::printf("## %s\n", msg.c_str());
    std::fflush(stdout);
}

/// Wait until the engine finishes thinking, or until `timeoutMs` elapses.
/// @return true if the engine is idle, false on timeout.
bool waitIdle(int timeoutMs)
{
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
    while (rapfi_is_thinking()) {
        if (std::chrono::steady_clock::now() >= deadline)
            return false;
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    return true;
}

}  // namespace

int main(int argc, char **argv)
{
    std::string configPath;
    bool        waitBetweenCommands = true;

    for (int i = 1; i < argc; ++i) {
        if (std::strcmp(argv[i], "--config") == 0 && i + 1 < argc) {
            configPath = argv[++i];
        }
        else if (std::strcmp(argv[i], "--no-wait") == 0) {
            waitBetweenCommands = false;
        }
        else if (std::strcmp(argv[i], "--help") == 0 || std::strcmp(argv[i], "-h") == 0) {
            std::printf("Usage: %s [--config <path>] [--no-wait] < commands.txt\n", argv[0]);
            return 0;
        }
    }

    if (rapfi_init(configPath.empty() ? nullptr : configPath.c_str(), printOutput, nullptr) != 0) {
        printDiag("ENGINE INIT FAILED");
        return 2;
    }
    printDiag("ENGINE READY");

    // NOTE: the bridge has replaced the global C++ std::cin / std::cout stream
    // buffers, so the harness must read its own input with C stdio (fgets).
    char        buf[4096];
    std::string line;
    while (std::fgets(buf, sizeof(buf), stdin)) {
        line = buf;
        // Strip trailing newline (and optional CR for Windows line endings).
        while (!line.empty() && (line.back() == '\n' || line.back() == '\r'))
            line.pop_back();

        // Skip blank lines and comments in command scripts.
        if (line.empty())
            continue;
        if (line[0] == '#')
            continue;

        if (waitBetweenCommands && rapfi_is_thinking()) {
            if (!waitIdle(60000)) {
                printDiag("TIMEOUT waiting for engine to finish previous search");
                rapfi_terminate();
                return 3;
            }
        }

        rapfi_send_command(line.c_str());
    }

    // Let any in-flight search finish before exiting.
    if (!waitIdle(120000)) {
        printDiag("TIMEOUT waiting for final search to finish");
        rapfi_terminate();
        return 4;
    }

    rapfi_terminate();
    printDiag("ENGINE TERMINATED");
    return 0;
}
