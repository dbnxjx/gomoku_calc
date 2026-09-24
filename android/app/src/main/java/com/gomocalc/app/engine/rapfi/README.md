# Rapfi 原生引擎接入说明

本目录是 Rapfi 引擎的全部接入代码，遵循主 README 的"目录边界"约定：所有 Rapfi 相关
改动都收在 `engine/rapfi` 与 `src/main/cpp/rapfi`，未改动 domain/model/viewmodel/UI。

## 分层

| 层 | 类 | 职责 |
| --- | --- | --- |
| 原生传输 | `RapfiJni` + `cpp/rapfi/jni` | 加载 `librapfi_jni.so`，命令/行输出管道 |
| 原生引擎层 | `JniRapfiNativeBridge` + `RapfiAnalyzer` | 把 `AnalysisRequest` 转成命令流，把 stdout 合并成 `EngineEvent` |
| 适配器 | `engine/RapfiEngineAdapter` | 注入 bridge，实现 `GomokuEngine`（无参构造已接真 bridge） |
| Web UI 插件 | `NativeRapfiPlugin` | Capacitor 插件，向打包页面透传 `initialize/sendCommand/stop` 与 stdout 事件 |

## 修改边界（本接入只动这些）

- `engine/rapfi/**`（含本文件、JNI/解析/插件）
- `engine/RapfiEngineAdapter.java`（无参构造接入真 bridge）
- `src/main/cpp/**`（CMake + Rapfi 源码 + bridge + JNI）
- `app/build.gradle`（新增 `externalNativeBuild.cmake` 段）
- `app/src/main/assets/capacitor.plugins.json`（注册 `NativeRapfi`）

未改动：`domain/`、`model/`、`viewmodel/`、`data/`、`MainActivity`、`capacitor*.gradle`、
`capacitor-android`、`capacitor-filesystem`、`assets/public/**`、以及 KataGo 相关文件。

## 注意

1. **引擎配置**：`RapfiJni.start(null)` 使用 Rapfi 内置的经典评估（自包含、无需 NNUE 权重）。
   需要 NNUE 时，把 `config.toml` + 权重放入 assets/自有目录，并把路径传给 `start(configPath)`。
2. **插件注册**：`capacitor.plugins.json` 是手工登记的。若日后在 Web 源码里重跑
   `cap sync`，需保证它重新生成后仍包含 `native-rapfi -> NativeRapfiPlugin` 这条映射，
   否则页面会报 `NativeRapfi` 插件不存在。
3. **坐标/视角**：Rapfi 输出的是"行棋方视角"，`RapfiAnalyzer` 已转成黑方视角再写入
   `AnalysisState`；棋盘坐标统一为左上角零基 `x/y`。
