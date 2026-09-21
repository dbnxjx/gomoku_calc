# Makefile for the native Linux build of the Rapfi engine bridge + CLI.
#
# This is the desktop verification build. The Android build uses CMake + NDK
# (see engine/CMakeLists.txt) and compiles the very same sources.
#
# Usage:
#   make            -> build build/rapfi-cli
#   make test       -> run the automated test suite (tests/run_tests.py)

RAPFI_DIR := ../engine/Rapfi
BRIDGE_DIR := ../engine/bridge

CXX      ?= g++
CC       ?= gcc
BUILD    := build

# x86-64 Linux: enable SSE4.1 (required by the NNUE kernels). On ARM64 Android
# the equivalent is -DUSE_NEON -march=armv8-a+simd (set by engine/CMakeLists.txt).
CXXFLAGS := -std=c++17 -O2 -DNDEBUG -DMULTI_THREADING -DUSE_SSE \
            -msse -msse2 -msse3 -mssse3 -msse4 -msse4.1 \
            -ffp-contract=off -pthread

INCLUDES := -I$(RAPFI_DIR) \
            -I$(RAPFI_DIR)/external/cpptoml/include \
            -I$(RAPFI_DIR)/external/simde/include \
            -I$(RAPFI_DIR)/external/lz4/include \
            -I$(RAPFI_DIR)/external/cxxopts/include \
            -I..

CORE_SOURCES := \
    command/argutils.cpp command/benchmark.cpp command/command.cpp \
    command/dbcommand.cpp command/gomocup.cpp \
    core/compressor.cpp core/hash.cpp core/iohelper.cpp core/utils.cpp \
    core/platform.cpp core/version.cpp \
    database/dbclient.cpp database/dbconfig.cpp database/dbutils.cpp \
    database/dbtypes.cpp database/renlib.cpp database/yxdbstorage.cpp \
    eval/eval.cpp eval/evalconfig.cpp eval/evaluator.cpp \
    eval/mix9svqnnue.cpp eval/mix10nnue.cpp \
    game/board.cpp game/movegen.cpp game/pattern.cpp \
    search/hashtable.cpp search/movepick.cpp search/opening.cpp \
    search/searchcommon.cpp search/searchconfig.cpp search/searchengine.cpp \
    search/searchoutput.cpp search/searchthread.cpp search/timecontrol.cpp \
    search/ab/history.cpp search/ab/search.cpp \
    search/mcts/node.cpp search/mcts/search.cpp \
    config.cpp internalConfig.cpp
# NOTE: Rapfi/main.cpp (the standalone engine entry point) is intentionally NOT
# compiled here - the bridge drives Command::GomocupProtocol::runProtocol()
# directly, and cli/main.cpp provides the harness entry point.

CPP_SOURCES := $(addprefix $(RAPFI_DIR)/,$(CORE_SOURCES)) \
               $(BRIDGE_DIR)/rapfi_bridge.cpp \
               main.cpp

C_SOURCES := $(RAPFI_DIR)/external/lz4/src/lz4_all.c \
             $(RAPFI_DIR)/external/lz4/src/xxhash.c

# Object files land flat inside build/ with a unique name derived from the
# source path (leading "../" stripped, '/' -> '_'), avoiding directory escapes.
cpp_obj = $(BUILD)/$(subst /,_,$(patsubst ../%,%,$(1))).o
c_obj   = $(BUILD)/$(subst /,_,$(patsubst ../%,%,$(1))).o

CPP_OBJECTS := $(foreach s,$(CPP_SOURCES),$(call cpp_obj,$(s)))
C_OBJECTS   := $(foreach s,$(C_SOURCES),$(call c_obj,$(s)))

TARGET := $(BUILD)/rapfi-cli

.PHONY: all clean test

all: $(TARGET)

# Generate one explicit compile rule per source file.
define CPP_RULE
$(call cpp_obj,$(1)): $(1)
	@mkdir -p $(BUILD)
	$(CXX) $(CXXFLAGS) $(INCLUDES) -c $(1) -o $$@
endef
$(foreach s,$(CPP_SOURCES),$(eval $(call CPP_RULE,$(s))))

define C_RULE
$(call c_obj,$(1)): $(1)
	@mkdir -p $(BUILD)
	$(CC) -O2 -I$(RAPFI_DIR)/external/lz4/include -c $(1) -o $$@
endef
$(foreach s,$(C_SOURCES),$(eval $(call C_RULE,$(s))))

$(TARGET): $(CPP_OBJECTS) $(C_OBJECTS)
	@mkdir -p $(BUILD)
	$(CXX) $(CXXFLAGS) $^ -o $@ -pthread -latomic

test: $(TARGET)
	python3 ../tests/run_tests.py --cli $(TARGET)

clean:
	rm -rf $(BUILD)
