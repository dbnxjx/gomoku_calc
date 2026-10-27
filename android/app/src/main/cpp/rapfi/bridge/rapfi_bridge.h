/*
 *  rapfi_bridge.h - C API for embedding the Rapfi engine as a native library.
 *
 *  This header mirrors the semantics of the web version's engine API:
 *    src/ai/engine.js  ->  init() / sendCommand() / stopThinking()
 *
 *  The engine speaks a line based text protocol over its stdio streams.
 *  This bridge redirects std::cin / std::cout / std::cerr to internal
 *  buffers and delivers every complete output line through the callback,
 *  exactly like the Emscripten preamble.js does for the web build.
 *
 *  Copyright (C) 2024. Licensed under GPLv3 (see Copying.txt).
 */

#pragma once

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Output callback. Called with one complete line of engine output
 * (the terminating newline is removed). The pointer is only valid
 * for the duration of the call.
 *
 * The callback may be invoked from arbitrary threads:
 *   - from the thread that called rapfi_send_command() for synchronous
 *     replies such as "OK" / "FORBID ...";
 *   - from the engine's internal search threads for asynchronous
 *     "INFO ..." / best-move output.
 * Implementations must therefore be thread-safe.
 */
typedef void (*rapfi_output_callback)(const char *line, void *userdata);

/**
 * Initialize the engine.
 *
 * @param config_path  Path to a TOML config file. Pass NULL or "" to use the
 *                     engine's built-in classical evaluation config (no NNUE,
 *                     self contained). Passing an explicit config allows
 *                     loading NNUE weights from the same directory.
 * @param cb           Output line callback (may be NULL).
 * @param userdata     Opaque pointer passed through to cb.
 * @return 0 on success, non-zero if the config failed to load.
 */
int rapfi_init(const char *config_path, rapfi_output_callback cb, void *userdata);

/**
 * Send one protocol command (without trailing newline), for example
 * "INFO RULE 0" or "YXBOARD 7,7,1 DONE".
 *
 * The command is fully consumed synchronously. A command that starts a search
 * (YXNBEST / YXBALANCEONE / ...) returns immediately and the results arrive
 * later through the output callback.
 */
void rapfi_send_command(const char *cmd);

/**
 * Stop the current search and wait for its best-move callback and search
 * threads to finish. Cancels automatic pondering for that search as well.
 * Call on a worker thread; subsequent commands may safely start a new search.
 */
void rapfi_stop_thinking(void);

/**
 * @return non-zero while the engine is searching / thinking.
 */
int rapfi_is_thinking(void);

/**
 * Terminate the engine: stop any search, wait for all search threads to exit
 * and destroy them. May be called more than once; safe to call from any thread.
 */
void rapfi_terminate(void);

#ifdef __cplusplus
}
#endif
