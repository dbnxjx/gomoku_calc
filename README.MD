# Gomoku Calculator Android

这是一个基于 Capacitor Web UI 的五子棋/连珠分析 Android 工程。Android 宿主在 `android/app`，网页资源已经放在 `android/app/src/main/assets/public`。

## 构建

当前仓库已经把构建所需的 Capacitor Android 模块放在仓库内，不再依赖 `_rebuild/node_modules`：

```text
android/capacitor-android
android/capacitor-filesystem
android/capacitor-cordova-android-plugins
```

提交时应包含整个 `android` 工程和 `android/gradle/wrapper`。对方克隆仓库后，在本机准备好 JDK 21、Android SDK API 36 和 Android SDK Build Tools 后执行：

```powershell
cd android
.\gradlew.bat :app:assembleDebug --no-daemon
```

APK 输出到：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

首次构建仍需要从 Google Maven 和 Maven Central 下载 AndroidX、Cordova 等外部依赖，因此需要网络或已经配置好的 Gradle 缓存。`android/local.properties` 只保存本机 SDK 路径，不应提交；没有该文件时可由 Android Studio 创建，或手动写入：

```properties
sdk.dir=D\:\\Android\\Sdk
```

以下内容是构建缓存或本机配置，不应提交：`android/.gradle`、`android/build`、`android/app/build`、`android/.idea`、`android/local.properties`、APK 和签名文件。`.gitignore` 已保留 Capacitor 模块、网页资源和 Capacitor 配置，因为它们在本仓库中是实际构建输入。

## 目录边界

```text
android/app/src/main/java/com/gomocalc/app/
  domain/       棋盘、棋谱和规则领域模型
  model/        引擎输出和显示数据模型
  engine/       引擎抽象、适配器和协议边界
  viewmodel/    原生层状态协调
android/app/src/main/assets/public/
                已构建的 Web UI，不要手改压缩后的 JS
android/capacitor-android/
android/capacitor-filesystem/
                仓库内置的 Capacitor Android library
```

`_rebuild` 不是 Android 构建依赖。不要把 `android` 工程重新指向 `_rebuild/node_modules`，也不要把仓库里的 Android 工程和其他 Compose 工程合并。

## 接入 Rapfi

Rapfi 的接入分为原生引擎层和现有 Web UI 插件层。两层都完成后，页面才会真正显示分析结果。

### 原生层需要修改

1. 在 `android/app/src/main/java/com/gomocalc/app/engine/rapfi/` 实现 `RapfiNativeBridge`。JNI、独立进程、线程、启动和停止、stdout/stderr 读取都放在该包内。
2. 在 `RapfiEngineAdapter` 中通过构造注入这个 bridge。适配器负责把 Rapfi 输出转换为通用的 `EngineEvent` 和 `AnalysisState`。
3. 如果使用 JNI，在 `android/app/src/main/cpp` 增加 CMake/JNI 代码，在 `android/app/src/main/jniLibs` 或 CMake 输出中提供目标 ABI 的 `.so`。引擎二进制、配置和模型文件放在 Rapfi 自己的资源目录，不要散落到 UI 代码中。
4. 保持坐标、统计量和视角的约定：坐标是从左上角开始的零基 `x/y`；`blackWinRate` 是黑方视角的 `[0, 1]`；`visits` 和 `nodes` 不得互相改名。

### 现有 Web UI 需要的插件契约

当前打包页面通过 Capacitor 注册 `NativeRapfi`，调用的是命令流接口：

```text
initialize({ engineType: "RAPFI" }) -> Promise
sendCommand({ command: string })   -> Promise
stop()                              -> Promise
addListener("stdout", listener)
addListener("stderr", listener)
addListener("status", listener)
addListener("exit", listener)
```

事件数据可以放在 `data`、`output` 或 `value` 字段。页面当前解析的 Rapfi 输出包括：`OK`、`SWAP`、`x,y`、`INFO PV`、`NUMPV`、`DEPTH`、`SELDEPTH`、`NODES`、`TOTALNODES`、`TOTALTIME`、`SPEED/VISITS`、`BLACK_WINRATE`、`EVAL`、`WINRATE`、`BESTLINE`、`FORBID` 和 `ERROR`。常用输入命令包括 `INFO ...`、`START size`、`BOARD/YXBOARD ... DONE`、`YXNBEST` 和 `YXSHOWFORBID`。

原生 Capacitor 插件必须叫 `NativeRapfi`，并注册到 Capacitor plugin registry；当前 `capacitor.plugins.json` 只注册了 Filesystem。插件注册和生成配置应通过正式的 Capacitor 同步流程完成；不要只修改 Java bridge 而假设页面会自动发现它。

## 接入 KataGo

1. 在 `android/app/src/main/java/com/gomocalc/app/engine/katago/` 实现 `KataGoNativeBridge`，负责 KataGo transport、worker 生命周期、模型加载和协议解析。
2. 在 `KataGoEngineAdapter` 中注入 bridge，统一输出 `AnalysisState`。需要提供真正支持本项目棋类和规则的后端/模型，不假定可以把围棋 KataGo 输出直接当作五子棋结果。
3. 如果页面继续使用命令流，需要增加并注册名为 `NativeKataGo` 的 Capacitor plugin，至少提供 `initialize`、`sendCommand`、`stop` 和 `stdout/stderr/status/exit` 事件；或者同步修改 Web 源码中的 adapter 和打包资源，改用新的 typed API。两种协议只能选一种并保持一致。
4. KataGo 特有的模型 ID、网络文件和参数只放在 KataGo adapter/bridge 的配置中。通用引擎接口只接收 `EngineOptions`，不要把 KataGo 参数扩散到棋盘渲染层。

## 稳定的原生引擎接口

引擎实现必须满足 `GomokuEngine`：

```java
EngineType getType();
boolean isAvailable();
String getUnavailableReason();
void initialize(EngineOptions options);
void analyze(AnalysisRequest request, GomokuEngine.Listener listener);
void stop();
void close();
```

`AnalysisRequest` 包含：

- `boardSize`
- 行优先棋盘 `board[y * boardSize + x]`，`0=空`、`1=黑`、`2=白`
- `sideToMove`，`1=黑`、`2=白`
- `EngineOptions`：规则、线程数、时间上限、访问次数上限

`AnalysisState` 是一次完整的不可变快照，字段包括：

| 字段 | 用途 |
| --- | --- |
| `depth` | 当前搜索深度 |
| `score` | 引擎评分，可为空 |
| `blackWinRate` | 黑方胜率，`0..1`，可为空 |
| `nodes` / `visits` | 节点数和访问数 |
| `visitsPerSecond` | 访问速度 |
| `candidates` | 候选点、评分、胜率、访问数和 PV |
| `heatmap` | 棋盘各点的黑方胜率和访问数 |
| `killPoints` | 强制/杀点、受益方、深度和原因 |
| `principalVariation` | 主变化序列 |
| `forbiddenPoints` | 禁手点 |
| `bestMove` | 当前最佳点 |

不要把协议中的零散记录直接交给 UI；adapter 应先合并成完整快照，再发出 `UPDATE` 或 `FINISHED` 事件。没有结果时使用 `null`，不要伪造 `50%`。

## UI 渲染接口

### 数据接口

网页渲染只依赖棋盘状态、分析输出和 `DisplayOptions`。`BoardPoint` 的坐标为零基 `x/y`，`x` 向右、`y` 向下。

`DisplayOptions` 提供以下显示开关：

```text
showCandidates       候选点和候选标签
showKillPoints       杀点/强制点
showHeatmap          热力图
showVisitsPerSecond  左上角 visits/s 信息
showAdvantageBar     黑白占优条
candidateLabel       SCORE / WIN_RATE / VISITS
```

### 棋盘 Canvas 分层

现有 Web Board 组件按三层绘制。引擎接入应只更新数据，不直接操作 Canvas。

| 接口 | 功能 |
| --- | --- |
| `drawBoardLayer` | 清理背景，绘制棋盘背景、坐标和网格 |
| `drawPieceLayer` | 绘制黑白棋子、手数、预览点、最后一步和胜利线 |
| `drawRealtimeLayer` | 绘制禁手、选中点、实时 BEST/LOST、候选点、PV 评分和树节点评分 |
| `redrawAllLayers` | 按顺序重绘三层 |
| `screenshot` | 导出当前棋盘截图 |
| `exportGIF` | 按棋谱和时间间隔导出 GIF |

页面状态中与渲染相关的输出键为：`pos`、`pv`、`forbid`、`heatmap`、`killPoints`、`nodes`、`speed`、`visitsPerSecond`、`blackWinRate` 和 `realtime`。引擎可以新增数据字段，但已有字段的坐标、黑方视角和单位不能改变。

### 不要改动的内容

为减少后续合并冲突，接入引擎时不要修改：

- `android/app/src/main/assets/public/**` 中的压缩网页资源。需要改 UI 时应修改对应 Web 源码，重新构建后整体同步资源。
- `android/app/capacitor.build.gradle`、`android/capacitor.settings.gradle` 的模块关系和本地路径。
- `android/capacitor-android`、`android/capacitor-filesystem` 中的第三方 Capacitor 源码。
- `domain/`、`model/` 和 `DisplayOptions` 的既有字段含义。
- `GomokuEngine`、`AnalysisRequest`、`AnalysisState`、`EngineEvent` 的方法、坐标和单位约定。
- `MainActivity` 的 Capacitor 启动方式，除非是在专门的 plugin registration 提交中修改。

引擎提交建议只包含 `engine/rapfi` 或 `engine/katago`、对应 JNI/CMake/原生插件和必要的资源；UI 提交单独处理 Web 源码与重新生成的 `public` 资源。这样两类改动可以独立合并。
