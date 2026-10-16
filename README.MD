# Gomoku Calculator Android

这是一个基于 Capacitor Web UI 的五子棋/连珠分析 Android 工程。Android 宿主在 `android/app`，网页源码在 `web/src`，构建后的静态资源同步到 `android/app/src/main/assets/public`，再由 Android WebView 加载。

## 构建

网页和 Android 是两步构建：先编译 Vue 页面，再编译 Android 宿主。仓库已经把构建所需的 Capacitor Android 模块放在仓库内，不再依赖 `_rebuild/node_modules`：

```text
android/capacitor-android
android/capacitor-filesystem
android/capacitor-cordova-android-plugins
```

网页源码的完整构建流程如下。`NODE_OPTIONS` 是旧版 Vue CLI 在较新 Node.js 上构建时需要的兼容参数：

```powershell
cd web
npm ci
$env:NODE_OPTIONS="--openssl-legacy-provider"
npm run build
Copy-Item -Recurse -Force dist\* ..\android\app\src\main\assets\public\
```

然后构建 Android。提交时应包含整个 `android` 工程和 `android/gradle/wrapper`。本机需要 JDK 21、Android SDK API 36 和 Android SDK Build Tools：

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
sdk.dir=D:\\Android\\Sdk
```

以下内容是构建缓存或本机配置，不应提交：`android/.gradle`、`android/build`、`android/app/build`、`android/.idea`、`android/local.properties`、APK 和签名文件。`.gitignore` 已保留 Capacitor 模块、网页资源和 Capacitor 配置，因为它们在本仓库中是实际构建输入。

也可以在仓库根目录执行 `.\build-local.ps1`，它会完成网页构建、清理旧 bundle、同步 WebView 资源和 Android Debug APK 构建。更具体的本机路径和安装说明见 [README-LOCAL.md](README-LOCAL.md)。

## 目录边界

```text
android/app/src/main/java/com/gomocalc/app/
  domain/       棋盘、棋谱和规则领域模型
  model/        引擎输出和显示数据模型
  engine/       引擎抽象、适配器和协议边界
  viewmodel/    原生层状态协调
android/app/src/main/assets/public/
                已构建的 Web UI，不要手改压缩后的 JS
web/src/          Vue 页面源码、棋盘、棋树、设置和引擎显示逻辑
web/public/       Web UI 的静态文件
web/dist/         `npm run build` 的临时输出，不提交
android/capacitor-android/
android/capacitor-filesystem/
                仓库内置的 Capacitor Android library
```

`_rebuild` 只是原始页面的来源目录，不是 Android 构建依赖。当前可维护的页面源码已经复制到 `web`，不要再通过脚本修改压缩后的 bundle，也不要把 `android` 工程重新指向 `_rebuild/node_modules`。

## 页面和棋谱行为

页面运行在 Capacitor 提供的 WebView 中。Vue 源码负责棋盘、落子树、设置和显示层；原生 Capacitor 插件只负责 Rapfi/KataGo 进程和命令流。页面改动应在 `web/src` 完成，之后重新执行网页构建并同步 `web/dist`，Android 打包时 WebView 会从 `assets/public` 读取这些文件。

落子树和棋谱只保留真实落子、颜色和分支。保存格式是 SGF，分析评分、胜率、深度、PV、访问数以及未实际落子的候选节点都不会写入 SGF；读取旧 JSON 棋谱时也会过滤这些计算字段。点击棋树节点只切换到对应局面，不会把分析候选点变成棋谱落子。

引擎相关的页面显示按引擎隔离：

- 选择 Rapfi 时保留原有方格分析、估值/胜率、杀点、禁手和 Rapfi 设置。
- 选择 KataGo 时隐藏 Rapfi 的思考、线程、权重、杀点和颜色设置，只保留候选点数量和热力图开关。
- KataGo 热力图实时显示候选圆点；每个点显示胜率、visits 和候选序号，第一候选点使用蓝色，其余使用绿色。
- 点击计算会持续接收 KataGo 流式结果；落子、悔棋或切换棋树局面会先更新引擎局面，再按当前暂停状态继续或停止分析。

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

原生 Capacitor 插件必须叫 `NativeRapfi`，并注册到 Capacitor plugin registry；当前 `capacitor.plugins.json` 已注册 Filesystem、`NativeRapfi` 和 `NativeKataGo`。插件注册和生成配置应通过正式的 Capacitor 同步流程完成；不要只修改 Java bridge 而假设页面会自动发现它。

## 接入 KataGo

当前 Android 页面通过已注册的 `NativeKataGo` Capacitor 命令流调用 KataGo。页面逻辑直接维护在 `web/src`，构建后整体同步到 WebView 静态资源，不再使用 bundle 补丁。

### Android 页面命令

原生插件启动长驻 ARM64 GTP 进程，首次启动会把约 270 MB 的 gzip 模型复制到 app 私有目录，并保存为 KataGo 需要的 `.bin.gz` 文件。模型在 Android assets 中使用 `.model` 后缀，避免 Gradle 自动解压 `.gz`；不要把模型 asset 改回 `.bin.gz`。插件注册名是 `NativeKataGo`，当前命令包括：

| 页面命令 | 行为 |
| --- | --- |
| `START size` | 设置棋盘大小并清空局面 |
| `YXBOARD x,y,color ... DONE` | 用完整局面替换当前盘面，`color` 为 `1=黑`、`2=白`，不开始搜索 |
| `SET_POSITION x,y,color ... DONE` | 同上；颜色和坐标被转换成 KataGo `set_position` 的成对参数 |
| `PLAY B x,y` / `PLAY W x,y` | 在当前局面继续落子，不清空盘面 |
| `UNDO`、`CLEAR_BOARD` | 按 KataGo GTP 语义悔棋或清盘 |
| `YXNBEST n` | 以当前行棋方启动持续 `kata-analyze`，流式返回最多 `n` 个候选点 |
| `YXSTOP` 或插件 `stop()` | 停止当前搜索；页面手动暂停同时关闭局面变化后的自动续算 |

点击页面的计算按钮会开启持续热力图分析。分析过程中可以直接落子，也可以悔棋或浏览落子树；页面先将完整新局面发送给引擎，再继续分析。只要用户没有手动暂停，这些局面变化都会重启分析。手动暂停后，后续落子和棋谱浏览都不会自动启动搜索；再次点击计算才会恢复。引擎候选点只作为热力图和分析输出，不会自动成为棋谱落子。

KataGo 下的平衡分析入口也启动当前局面的持续分析，不会自动替用户落子；Rapfi 的权重配置重载命令只适用于 Rapfi，KataGo 始终使用自己的 GTP 配置文件。切换引擎会停止此前后端的搜索；切换回 KataGo 后需再次点击计算。

### KataGo 流式输出

插件把 GTP `kata-analyze` 的 `info` 行转换成现有 Rapfi 页面解析器支持的输出。热力图事件为 JSON 数组，每项是 `[x,y,blackWinRate,visits,rank]`；坐标从左上角起算且从零开始，胜率为黑方视角的 `0..1`，访问数是 KataGo visits，`rank` 是 KataGo 的候选顺序（从 `1` 开始）。页面实时绘制候选圆点：第 1 点为蓝色，其余候选点为绿色，圆点中心显示胜率和访问数，左上角显示候选序号。

在设置中选择 KataGo 后，可以使用“自动测速并保存”。测速会重启 KataGo，依次比较搜索线程、OpenCL 后端线程和批大小的组合，固定使用 FP32，并保存最快配置；当前会话和以后启动都会使用该配置。测速过程中页面会显示运行状态，结果和进度会写入主页调试日志。流式分析按 KataGo 分支要求使用 `kata-analyze <side> 10 maxmoves <n> pvVisits true`，分析间隔单位为厘秒。

| 页面输出 | 含义 |
| --- | --- |
| `INFO PV 0` / `INFO PV DONE` | 一条候选分析的开始和结束 |
| `INFO BESTLINE x,y ...` | 候选变化，首点是候选落子 |
| `INFO WINRATE value`、`INFO BLACK_WINRATE value` | 黑方胜率，范围 `0..1` |
| `INFO NODES value` / `INFO TOTALNODES value` | 当前候选访问数 / 总访问数；KataGo 这里的计数来自 `visits` |
| `INFO SPEED value` | 搜索期间估算的 visits/s |
| `HEATMAP [[x,y,winrate,visits,rank],...]` | 当前局面的实时候选热力图快照 |
| `ERROR message` | GTP 启动或命令错误 |

暂停仅结束本次分析，不退出长驻引擎，因此不需要为下一局面重新加载模型。当前配置固定使用 `openclUseFP16 = false`，并将模型和引擎版本放在 KataGo 私有插件资源中；不要把 KataGo 参数加进 Rapfi 通用设置。

独立的 `GomokuEngine`/`AnalysisState` Java 接口仍是领域层接口；当前 Web 页面没有调用 typed API，而是使用上面的 Capacitor 命令流。若之后改为 typed API，必须同时实现 adapter 到 `AnalysisState` 的转换并删除旧的页面命令适配，不能让两套入口并行控制同一搜索。

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
