# rapfi-android-engine

安卓原生 Rapfi 五子棋引擎（NDK + JNI），引擎协议与开源网页版
[五子棋计算器](https://github.com/dhbloo/gomoku-calculator)（`gomoku-calculator`）完全一致。

网页版把 Rapfi（C++）用 Emscripten 编译成 WASM 运行在浏览器里；本项目把**同一个
Rapfi C++ 引擎**用 Android NDK 编译成原生 `.so`，通过 JNI 暴露给 Kotlin，摆脱 WASM
的性能损耗，实现真正的"手机本地原生引擎"。

---

## 一、项目结构

```
rapfi-android-engine/
├── engine/                          # 原生引擎
│   ├── Rapfi/                       # 未修改的 Rapfi C++ 引擎源码 (GPLv3)
│   ├── bridge/rapfi_bridge.{h,cpp}  # 引擎嵌入层：重定向 stdio，暴露 C API
│   ├── jni/rapfi_jni.cpp            # JNI 封装，暴露给 Kotlin
│   └── CMakeLists.txt               # NDK 构建脚本（产出 librapfi_jni.so）
├── app/                             # Android Demo 工程（Kotlin）
│   ├── build.gradle                 # 引用 engine/CMakeLists.txt
│   └── src/main/java/com/gomoku/rapfi/
│       ├── RapfiEngine.kt           # 对标网页版 src/ai/engine.js 的 API
│       ├── EngineEvent.kt           # 对标网页版 onEngineStdout 的输出解析
│       └── MainActivity.kt          # 真机自检 Demo
├── cli/                             # Linux 桌面验证程序（同一套引擎）
│   ├── main.cpp                     # 命令行测试脚手架
│   └── Makefile                     # g++ 构建脚本
└── tests/run_tests.py               # 自动化验证脚本
```

## 二、API 对照（网页版 → 安卓原生版）

| 网页版 `src/ai/engine.js`      | 安卓原生 `RapfiEngine.kt`                     |
| ------------------------------ | --------------------------------------------- |
| `init(callback, loadFull)`     | `init(configPath, listener, handler)`         |
| `sendCommand(cmd)`             | `sendCommand(cmd)`                            |
| `stopThinking()`               | `stopThinking()`                              |
| 回调 `{pos:[x,y]}`             | `EngineEvent.Pos(x, y)`                       |
| 回调 `{depth}` / `{eval}` ...  | `EngineEvent.Depth` / `Eval` / `BestLine` ... |
| 回调 `{forbid}`                | `EngineEvent.Forbid`                          |
| 回调 `{ok:true}`               | `EngineEvent.Ready`                           |

**协议命令完全一致**，可直接复用网页版的落子/分析逻辑：

```
START 15
INFO RULE 0          INFO THREAD_NUM 1    INFO TIMEOUT_TURN 5000
INFO MAX_DEPTH 100   INFO SHOW_DETAIL 3
YXBOARD 7,7,1 DONE
YXNBEST 3            YXBALANCEONE 0       YXSHOWFORBID      YXSTOP
```

## 三、Kotlin 使用示例

```kotlin
val engine = RapfiEngine()

engine.init(configPath = null,   // null = 使用内置经典评估(免 NNUE 权重)
            listener = { event -> when (event) {
                is EngineEvent.Ready    -> println("ready")
                is EngineEvent.Depth    -> /* 实时深度 */
                is EngineEvent.BestLine -> /* 实时主变 */
                is EngineEvent.Pos      -> println("best move ${event.x},${event.y}")
                is EngineEvent.Forbid   -> /* 禁手点 */
                else -> {}
            }},
            handler = Handler(Looper.getMainLooper()))  // 事件回主线程

engine.sendCommand("START 15")
engine.sendCommand("INFO RULE 0")
engine.sendCommand("YXBOARD 7,7,1 DONE")
engine.sendCommand("YXNBEST 3")
// ...
engine.terminate()
```

## 四、验证方法（你亲自验证）

### 方式 A：Linux 桌面验证（无需安卓设备/NDK，本仓库已通过）

只需 `g++` + `make` + `python3`：

```bash
cd cli
make            # 编译出 build/rapfi-cli（同一套引擎源码）
make test       # 运行自动化测试，期望输出 3/3 tests passed
```

`make test` 会验证三件事：`START` 应答 `OK`、`YXNBEST` 输出 `INFO` 与最终落子、
renju 规则下 `YXSHOWFORBID` 正确报告双三禁手点 `0707`。

手动交互验证（把命令喂给引擎，观察 `OUT ...` 行）：

```bash
printf 'START 15\nINFO RULE 0\nINFO TIMEOUT_TURN 2000\nINFO MAX_DEPTH 8\nINFO SHOW_DETAIL 3\nYXBOARD 7,7,1 DONE\nYXNBEST 3\n' | ./build/rapfi-cli
```

期望看到 `OUT OK`、一串 `OUT INFO ...`、以及最后一行 `OUT x,y`（最佳落子）。

### 方式 B：Android 真机验证

1. 安装 Android Studio + NDK（SDK Manager → SDK Tools → NDK & CMake）。
2. 用 Android Studio 打开本项目根目录 `rapfi-android-engine`。
3. 等待 Gradle 同步，直接 `Run` 到手机。
4. `MainActivity` 会自动跑一遍自检（init → START → 分析 → 最佳落子），
   屏幕上显示 `[PASS] engine produced a best move` 即验证通过。

> 版本兼容性（已按官方兼容表固定）：**Gradle 9.0 + AGP 8.13.0 + Kotlin 2.3.0**。
> 项目自带 Gradle Wrapper（`gradle/wrapper/gradle-wrapper.properties` 固定 Gradle 9.0.0），
> 打开时会自动使用该版本，避免被本机其他 Gradle 版本干扰。
> 如果你的 Android Studio 自带的 Gradle 不是 9.0，也可用命令行 `./gradlew :app:assembleDebug`
> 强制使用项目锁定的 Gradle 版本。

命令行构建（可选）：

```bash
# 设置 NDK 路径后
export ANDROID_NDK=$ANDROID_HOME/ndk/<版本>
cmake -B engine/build -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DCMAKE_BUILD_TYPE=Release engine
cmake --build engine/build
# 产物: engine/build/librapfi_jni.so
```

### 方式 C：逐条协议验证（对照网页版）

把下面脚本喂给 `cli/build/rapfi-cli`，对照 `gomoku-calculator/src/ai/engine.js`
里的解析逻辑逐行核对：

```
START 15
INFO RULE 2
YXBOARD 6,6,1 0,0,2 8,8,1 1,0,2 6,8,1 2,0,2 8,6,1 3,0,2 DONE
YXSHOWFORBID
```

期望 `OUT FORBID 0707.`（黑棋双三点 (7,7) 被判禁手）。

## 五、NNUE 权重（可选）

默认 `init(configPath = null)` 使用 Rapfi 内置的**经典评估**（`internalConfig.cpp`，
自包含、无需额外文件，棋力足够日常分析）。若要启用更强的 NNUE 评估：

1. 从 <https://github.com/dhbloo/rapfi-networks> 获取 `config.toml` 与权重文件，
   连同权重一起放到 App 可访问的目录（如 assets 解压到 filesDir）。
2. `init(configPath = "/data/data/<包名>/files/config.toml", ...)`。

## 六、许可

Rapfi 引擎采用 **GPL v3**（见 `Copying.txt` / `engine/Rapfi/Copying.txt`）。
分发本项目的二进制时必须同时提供完整源码或源码链接；对源码的修改也须按 GPLv3 开源。
本项目中的桥接层、JNI 封装与 Kotlin API 同样以 GPLv3 发布。
