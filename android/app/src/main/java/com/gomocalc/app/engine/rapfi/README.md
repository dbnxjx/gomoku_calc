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

## 跨会话分析结果保存（Yixinboard 数据库）

Rapfi 内置了 Yixinboard 兼容的分析数据库（`database/yixindb`），会在搜索时自动读取已
分析过的局面、并写入新的分析结论，实现**跨会话保留**。本项目复用它，不重新实现格式。

实现要点：

1. **引擎侧配置**：`cpp/rapfi/Rapfi/internalConfig.cpp` 的编译内置配置中新增了
   `[database]` 段（`type = "yixindb"`、`enable_by_default = false`，以及
   `[database.search]` 的读写阈值）。`enable_by_default = false` 保证引擎不会在 App
   给出真实路径前，去打开一个相对路径的 `rapfi.db`。
2. **运行期启用**：`RapfiJni.start()` 在加载引擎后，会向引擎发送
   `YXSETDATABASE <绝对路径>`。路径由 App 层提供（`NativeRapfiPlugin` 用
   `getFilesDir()/rapfi.db`，App 私有目录，无需存储权限）。
3. **落盘（flush）**：`NativeRapfiPlugin` 在每次搜索结束（收到 `x,y` 最佳着法）后延迟
   发送 `YXSAVEDATABASE`。引擎内部有 dirty 判断，没有新记录时是**零开销空操作**，
   有新记录时才写文件并打印 `DATABASE SAVE START/DONE`。
4. **UI 展现**：引擎会输出 `MESSAGE Opening yixin database at ...`、
   `MESSAGE Loaded Yixin database (N records) using ...`、`DATABASE SAVE DONE` 等行，
   这些会原样转发到页面，显示在**右上角的引擎消息抽屉里**。此外插件在每次开始分析时
   额外发出 `status` 事件 `Rapfi database: N positions cached (cross-session)`，
   保证"缓存了多少局面"在每次分析时都可见（页面每次 `think` 会清空旧消息）。

### 可选的读写开关

数据库默认读写都开。迁移到将来 UI 提交时可用的插件方法：

| 方法 | 作用 | 对应引擎命令 |
| --- | --- | --- |
| `setDatabaseEnabled({ enabled })` | 整体启用/停用数据库 | `INFO USEDATABASE 1/0` |
| `setDatabaseReadOnly({ readOnly })` | 只读：仍读缓存、不再写入 | `INFO DATABASE_READONLY 1/0` |
| `getDatabaseStatus()` | 返回 `{configured, path, recordCount}` | 仅读取插件侧状态 |

> 当前打包页面（v0.31.0 构建）没有数据库设置项，因此这些方法暂未被页面调用；
> 它们是给后续"UI 提交"用的接口（引擎/UI 提交分离，便于独立合并）。

## 修改边界

本接入只动这些：

- `engine/rapfi/**`（含本文件、JNI/解析/插件）
- `engine/RapfiEngineAdapter.java`（无参构造接入真 bridge）
- `src/main/cpp/**`（CMake + Rapfi 源码 + bridge + JNI）
- `src/main/cpp/rapfi/Rapfi/internalConfig.cpp`（新增 `[database]` 段）
- `app/build.gradle`（新增 `externalNativeBuild.cmake` 段）
- `app/src/main/assets/capacitor.plugins.json`（注册 `NativeRapfi`）

未改动：`domain/`、`model/`、`viewmodel/`、`data/`、`MainActivity`、`capacitor*.gradle`、
`capacitor-android`、`capacitor-filesystem`、`assets/public/**`、以及 KataGo 相关文件。

## 注意事项

1. **引擎配置**：`RapfiJni.start(null)` 使用 Rapfi 内置的经典评估（自包含、无需 NNUE
   权重）。需要 NNUE 时，把 `config.toml` + 权重放入 assets/自有目录，并把路径传给
   `start(configPath)`；同时该 config 也需要带 `[database]` 段。
2. **插件注册**：`capacitor.plugins.json` 是手工登记的。若日后在 Web 源码里重跑
   `cap sync`，需保证它重新生成后仍包含 `native-rapfi -> NativeRapfiPlugin` 这条映射，
   否则页面会报 `NativeRapfi` 插件不存在。
3. **坐标/视角**：Rapfi 输出的是"行棋方视角"，`RapfiAnalyzer` 已转成黑方视角再写入
   `AnalysisState`；棋盘坐标统一为左上角零基 `x/y`。
4. **数据库文件**：位于 `getFilesDir()/rapfi.db`（可另有一个 `rapfi_bak1.db` 备份）。
   卸载 App 会一并清除；需要用户可见/可导出的位置时可改用 `getExternalFilesDir()`。
