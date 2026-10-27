/*
 *  rapfi_bridge.cpp - implementation of the Rapfi embedding bridge.
 *
 *  Strategy (mirrors Rapfi/emscripten/preamble.js):
 *    - Replace std::cin's streambuf with a thread-safe queue fed by
 *      rapfi_send_command().
 *    - Replace std::cout / std::cerr streambufs with a line collector that
 *      invokes the output callback for every complete line.
 *    - Drive the engine by calling Command::GomocupProtocol::runProtocol()
 *      once per command, exactly like gomocupLoopOnce() does in the WASM
 *      single-threaded build.
 *
 *  Copyright (C) 2024. Licensed under GPLv3 (see Copying.txt).
 */

#include "rapfi_bridge.h"

#include "../Rapfi/command/command.h"
#include "../Rapfi/search/searchthread.h"
#include "../Rapfi/tuning/tunemap.h"

#include <atomic>
#include <deque>
#include <iostream>
#include <mutex>
#include <streambuf>
#include <string>
#include <vector>

// runProtocol() is defined inside namespace Command::GomocupProtocol in
// gomocup.cpp with external linkage. It is the same entry point the WASM
// build exports as gomocupLoopOnce.
namespace Command {
namespace GomocupProtocol {
extern std::atomic_bool thinking;
bool runProtocol();
void stopThinkingAndWait();
}  // namespace GomocupProtocol
}  // namespace Command

namespace {

// ---------------------------------------------------------------------------
// Input streambuf: a queue of command strings. Returns EOF when empty so a
// malformed command can never block forever (the protocol loop treats EOF as
// "stop reading this command", matching the WASM stdin contract).
// ---------------------------------------------------------------------------
class QueueInputStreambuf : public std::streambuf
{
public:
    void reset()
    {
        std::lock_guard<std::mutex> lk(mtx_);
        queue_.clear();
        buf_.clear();
        pos_ = 0;
    }

    void push(const std::string &s)
    {
        std::lock_guard<std::mutex> lk(mtx_);
        queue_.push_back(s);
    }

protected:
    int_type underflow() override
    {
        std::lock_guard<std::mutex> lk(mtx_);
        for (;;) {
            if (pos_ < buf_.size())
                return static_cast<unsigned char>(buf_[pos_]);
            if (queue_.empty())
                return traits_type::eof();
            buf_ = std::move(queue_.front());
            queue_.pop_front();
            pos_ = 0;
        }
    }

    int_type uflow() override
    {
        int_type c = underflow();
        if (c != traits_type::eof()) {
            std::lock_guard<std::mutex> lk(mtx_);
            ++pos_;
        }
        return c;
    }

private:
    std::mutex              mtx_;
    std::deque<std::string> queue_;
    std::string             buf_;
    size_t                  pos_ = 0;
};

// ---------------------------------------------------------------------------
// Output streambuf: accumulates characters until a newline, then emits the
// line through the callback. Thread-safe.
// ---------------------------------------------------------------------------
class CallbackOutputStreambuf : public std::streambuf
{
public:
    CallbackOutputStreambuf(rapfi_output_callback cb, void *ud) : cb_(cb), ud_(ud) {}

protected:
    int_type overflow(int_type c) override
    {
        std::string line;
        bool        hadNewline = false;
        {
            std::lock_guard<std::mutex> lk(mtx_);
            if (c == traits_type::eof())
                return traits_type::not_eof(c);
            if (c == '\n') {
                line       = std::move(buf_);
                buf_.clear();
                hadNewline = true;
            }
            else {
                buf_.push_back(static_cast<char>(c));
            }
        }
        if (hadNewline)
            emit(line);
        return traits_type::not_eof(c);
    }

    std::streamsize xsputn(const char *s, std::streamsize n) override
    {
        std::vector<std::string> lines;
        {
            std::lock_guard<std::mutex> lk(mtx_);
            for (std::streamsize i = 0; i < n; ++i) {
                if (s[i] == '\n') {
                    lines.push_back(std::move(buf_));
                    buf_.clear();
                }
                else {
                    buf_.push_back(s[i]);
                }
            }
        }
        for (const auto &l : lines)
            emit(l);
        return n;
    }

    int sync() override { return 0; }  // no internal buffering to flush

private:
    void emit(const std::string &line)
    {
        if (cb_)
            cb_(line.c_str(), ud_);
    }

    std::mutex           mtx_;
    std::string          buf_;
    rapfi_output_callback cb_;
    void                *ud_;
};

// ---------------------------------------------------------------------------
// Global state
// ---------------------------------------------------------------------------
std::mutex               g_commandMutex;  // serializes protocol iterations
QueueInputStreambuf     *g_inBuf  = nullptr;
CallbackOutputStreambuf *g_outBuf = nullptr;
std::streambuf          *g_oldCin  = nullptr;
std::streambuf          *g_oldCout = nullptr;
std::streambuf          *g_oldCerr = nullptr;
bool                     g_initialized = false;

}  // namespace

extern "C" {

int rapfi_init(const char *config_path, rapfi_output_callback cb, void *userdata)
{
    if (g_initialized)
        return 0;

    // Install the stdio redirects first so config loading output is captured.
    g_inBuf  = new QueueInputStreambuf();
    g_outBuf = new CallbackOutputStreambuf(cb, userdata);

    g_oldCin  = std::cin.rdbuf(g_inBuf);
    g_oldCout = std::cout.rdbuf(g_outBuf);
    g_oldCerr = std::cerr.rdbuf(g_outBuf);

    // The engine resolves config files relative to the binary directory;
    // provide a dummy argv so the path helpers work in an embedded context.
    {
        char  dummyArg[] = "rapfi";
        char *argv[]     = {dummyArg, nullptr};
        Command::CommandLine::init(1, argv);
    }

    if (config_path && config_path[0] != '\0') {
        Command::configPath          = config_path;
        Command::allowInternalConfig = false;
    }
    // else: keep default (config.toml) with internal-config fallback, which is
    // the self-contained classical-evaluation build.

    if (!Command::loadConfig()) {
        // Restore streams and report failure.
        std::cin.rdbuf(g_oldCin);
        std::cout.rdbuf(g_oldCout);
        std::cerr.rdbuf(g_oldCerr);
        delete g_inBuf;
        delete g_outBuf;
        g_inBuf  = nullptr;
        g_outBuf = nullptr;
        g_oldCin = g_oldCout = g_oldCerr = nullptr;
        return 1;
    }

    Tuning::TuneMap::init();

    g_initialized = true;
    return 0;
}

void rapfi_send_command(const char *cmd)
{
    if (!g_initialized || !cmd || cmd[0] == '\0')
        return;

    std::lock_guard<std::mutex> lk(g_commandMutex);

    // runProtocol ignores commands while thinking and consumes only their first
    // token. Finish the old search (including its best-move callback) before
    // handing it a new command, without holding the protocol's own mutex.
    Command::GomocupProtocol::stopThinkingAndWait();
    g_inBuf->reset();
    g_inBuf->push(std::string(cmd) + "\n");
    std::cin.clear();  // reset any EOF/error state left by a previous command
    Command::GomocupProtocol::runProtocol();
    // Each bridge call owns its entire command, even if parsing stops early.
    g_inBuf->reset();
}

void rapfi_stop_thinking(void)
{
    std::lock_guard<std::mutex> lk(g_commandMutex);
    Command::GomocupProtocol::stopThinkingAndWait();
}

int rapfi_is_thinking(void)
{
    return Command::GomocupProtocol::thinking.load(std::memory_order_relaxed);
}

void rapfi_terminate(void)
{
    Search::Engine.stopThinking();
    Search::Engine.waitForIdle();
    Search::Engine.setNumThreads(0);

    if (g_initialized) {
        std::cin.rdbuf(g_oldCin);
        std::cout.rdbuf(g_oldCout);
        std::cerr.rdbuf(g_oldCerr);
        delete g_inBuf;
        delete g_outBuf;
        g_inBuf  = nullptr;
        g_outBuf = nullptr;
        g_oldCin = g_oldCout = g_oldCerr = nullptr;
        g_initialized = false;
    }
}

}  // extern "C"
