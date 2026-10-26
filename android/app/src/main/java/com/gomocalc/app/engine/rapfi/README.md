# Rapfi 原生引擎接入说明

本目录是 Rapfi 引擎的全部接入代码，遵循主 README 的"目录边界"约定：所有 Rapfi 相关
改动都收在 `engine/rapfi`、`src/main/cpp/rapfi`、`src/main/assets/rapfi/`（NNUE 权重包）
与 Settings.vue 中的 Rapfi 专属数据库设置，未改动 domain/model/viewmodel/KataGo UI
与打包网页资源。

## 分层

| 层 | 类 | 职责 |
| --- | --- | --- |
| 原生传输 | `RapfiJni` + `cpp/rapfi/jni` | 加载 `librapfi_jni.so`，命令/行输出管道 |
| 资源包 | `RapfiAssets` | 首次运行把 APK 内的 NNUE 权重解到 `filesDir/rapfi` 并改写为绝对路径 |
| 原生引擎层 | `JniRapfiNativeBridge` + `RapfiAnalyzer` | `AnalysisRequest` → 命令流，stdout → `EngineEvent` |
| 适配器 | `engine/RapfiEngineAdapter` | 注入 bridge，实现 `GomokuEngine` |
| Web UI 插件 | `NativeRapfiPlugin` | 命令透传 + 引擎初始化 + 数据库管理 |
| 设置面板 | `web/src/views/Settings.vue` | 仅在 Rapfi 引擎下显示数据库设置 |

---

## 一、NNUE 评估（棋力）

引擎默认走 **mix9svq NNUE**，权重随 APK 打包，无需联网。

- **权重来源**：`rapfi-config/`（来自 <https://github.com/dhbloo/rapfi-networks>，CC0）。
- **打包内容**：`src/main/assets/rapfi/`

  | 文件 | 大小 | 说明 |
  | --- | --- | --- |
  | `config.toml` | 7 KB | `type = "mix9svq"` + `binary_file` + 数据库段 |
  | `model210901.bin` | 30 KB | 经典评估模型 |
  | `mix9svqfreestyle_bsmix.bin.lz4` | 10 MB | 无禁手 |
  | `mix9svqstandard_bs15.bin.lz4` | 10 MB | 无禁长连 |
  | `mix9svqrenju_bs15_black/white.bin.lz4` | 各 10 MB | 连珠黑/白 |

  共约 39 MB。`app/build.gradle` 对 `lz4`/`bin` 设了
  `androidResources { noCompress += [...] }`（**必须用这个 AGP 8 的写法**，旧的
  `aaptOptions.noCompress` 可能不生效），避免 AAPT 二次压缩导致读取截断。

- **首次运行**：`RapfiAssets.ensureExtracted()` 按**显式清单**逐个拷贝到 `filesDir/rapfi/`，
  校验大小（权重 < 5 MB 判为截断），然后把 `config.toml` 里的 `weight_file*` / `binary_file`
  **改写成绝对路径**再交给引擎。用 `.extracted` 标记版本（当前 `mix9svq-3`）。
- **兜底**：资源缺失/截断时回退到内置经典评估（`internalConfig.cpp`），引擎仍可用，并在
  消息里说明原因。
- **异步**：解包 + 加载耗时较长，`initialize` 在后台线程执行，完成后才 resolve Promise。

> **坐标模式（重要）**：配置使用 `coord_conversion_mode = "X_flipY"`。这是 gomoku-calculator
> 官方配置的取值（其 `gomocalc-*.toml` 全部如此），因为网页棋盘 y 轴向下、Rapfi 内部 y 轴
> 向上。用错模式会把局面上下镜像；经典评估近似对称所以不明显，但 **NNUE 方向敏感，用错会
> 明显掉棋力**。

### 引擎权重（复用设置页已有的选项）

打包页面的设置页本来就有「**引擎权重**」一项（`setting.thinking.config.title`），选项为
`Mix9svq NNUE` / `传统(2022/07/23)` / `传统(2021/09/01)`，对应 `CONFIGS =
["config.toml", "classical220723.toml", "classical210901.toml"]`。只是它被
`fullEngine` 标志（引擎启动后被置为 `false`）把它中的 NNUE 选项隐藏了。

因此本模块复用设置页已有的权重条目，并在 Rapfi 引擎下显示完整选项：

1. Settings.vue 在 Rapfi 模式下显示 `Mix9svq NNUE` 和两套 classical 选项。
2. 插件**拦截 `RELOADCONFIG <名字>`**，把名字映射到解包后的绝对路径：
   | 页面选项 | 名字 | 实际配置 |
   | --- | --- | --- |
   | Mix9svq NNUE | `config.toml` | `mix9svq` 评估 |
   | 传统(2022/07/23) | `classical220723.toml` | 经典 + `model220723.bin` |
   | 传统(2021/09/01) | `classical210901.toml` | 经典 + `model210901.bin` |
3. 重载会按 `enable_by_default = false` 把数据库摘掉，所以紧接着**重发 `YXSETDATABASE`**。
   选中的权重用 SharedPreferences 持久化，重启后仍然生效。

### 排查：`failed to load nnue weight from xxx.bin.lz4`

关键看**路径是相对还是绝对**：

- **相对路径**（如 `from mix9svqfreestyle_bsmix.bin.lz4`）⇒ 引擎在配置目录里**没找到文件**
  （`getModelFullPath()` 找不到时会原样返回相对路径）。权重没解出来或被截断。
- **绝对路径** ⇒ 文件在但读不了/内容坏。

现在配置已被改写成绝对路径，所以报错里必然带绝对路径，可直接区分。处理步骤：

```bash
# APK 里有没有权重、是否被压缩（Method 应为 Stored）
unzip -v app/build/outputs/apk/debug/app-debug.apk | grep rapfi
# 解包到设备后的实际大小
adb shell run-as com.gomocalc.app ls -l files/rapfi/
# 解包日志
adb logcat -s RapfiAssets
```

App 内也可以点右下角 `DB` 按钮 →「引擎评估 / Engine」区块查看每个权重的实际大小。

> `Evaluator set to mix9svq.` 只表示**配置解析成功**；真正的成功标志是
> `mix9svq nnue: weight loaded in ~90ms`，并且**没有** `Evaluator mix9svq disabled`。

### 排查：`Error: Failed to load config. Please check if config file is correct.`

这是打包页面在每次开始分析前发送 `RELOADCONFIG config.toml`（相对文件名）导致的：该文件
不在磁盘上，重载失败，而且重载会把数据库摘掉。插件现在**拦截 `RELOADCONFIG`**（不再转发
给引擎），改由 NNUE 开关通过 `applyEngineConfig()` 决定加载哪份配置，因此这个报错不会再出现。

---

## 二、跨会话分析结果保存

> **数据库目录**：引擎在写库时**不会自己创建父目录**，`filesDir/databases/` 不存在就会打印
> `Failed to open YXDB file at ...`。插件在初始化（以及建库/导入/切换前）会先 `mkdirs()`，
> 这个报错不会再出现。

Rapfi 内置 Yixinboard 兼容的分析数据库（`yixindb`）：搜索时自动读取已分析过的局面、
写入新的分析结论，跨会话保留。本项目复用它，不重新实现存储格式。

1. **配置**：`config.toml`（NNUE）与 `internalConfig.cpp`（经典）里都有
   `[database] type = "yixindb"`、`enable_by_default = false`。
2. **运行期启用**：`RapfiJni.start()` 后发 `YXSETDATABASE <绝对路径>`，默认
   `filesDir/databases/default.db`（App 私有目录，无需权限）。
3. **落盘**：每次搜索结束（收到 `x,y`）后延迟 300ms 发 `YXSAVEDATABASE`；引擎内部有 dirty
   判断，无新记录时是零开销空操作。
4. **UI 展现**：`Loaded Yixin database (N records)`、`DATABASE SAVE DONE` 会转发到页面消息
   抽屉；开始分析时插件额外发 `status`：
   `Rapfi database [default]: N positions cached (cross-session)`。

---

## 三、保存限制（不是每一步都入库）

只存"搜得够深"的结果、不存"没算清的必杀"。参考 `config_classical.toml` 的
`[database.search]`，两份配置都取下列值：

| 参数 | 值 | 含义 |
| --- | --- | --- |
| `pv_write_ply` | 0 | 主要分支只在根线路写 |
| `pv_write_min_depth` | 10 | 主要分支最低写入深度 10 |
| `min_write_depth` | 10 | **任意记录（含根节点）的绝对最低深度** |
| `write_value_range` | 800 | 分数超出该范围不写 |
| `mate_write_ply` | 2 | 必胜必败分支写入层数 |
| `mate_write_min_depth_exact` | 10 | 已算清（准确步数）必杀最低深度 |
| `mate_write_min_depth_nonexact` | 100 | 未算清必杀最低深度；取很大值 = **不写入这类记录** |
| `mate_write_min_step` | 2 | 少于 2 步的必胜必败不记录 |
| `overwrite_rule` | `better_value_depth_bound` | 覆盖策略 |

> **为什么新增 `min_write_depth`？** Rapfi 原版**根节点会绕过** `pv_write_min_depth`
> 等分支门槛（`writeDatabaseRecord` 里的 `RootNode ||`），一次浅搜索也会把根局面的低质量
> 结果写库。新增的 `min_write_depth` 在写库入口对**所有**记录（含根节点）生效；
> 默认值 0 表示保持原版行为。
> 涉及文件：`database/dbconfig.h`、`search/searchengine.{h,cpp}`、`config.cpp`、
> `search/ab/search.cpp`。升级 Rapfi 源码时需重新套用这几处。

**实测**：浅搜索（深度 8）→ `(0 records)`；深搜索（深度 17）→ 写入；只读模式 → `(0 records)`。

---

## 四、数据库设置（设置页）

Settings.vue 直接使用页面已有的 `group`、`cell`、`popup-radio` 和 `x-button` 控件，
仅在 `engineType === 'RAPFI'` 时显示数据库设置；切换到 KataGo 后整组设置消失。

卡片提供（均为 `weui-cell` 行，右侧为值/操作）：

| 行 | 内容 |
| --- | --- |
| 使用方式 | 下拉：使用数据库 / 只读数据库 / 不使用数据库 |
| 当前数据库 | `default（N 条）` |
| 切换数据库 | 下拉选择即切换 |
| 新建数据库 | 输入名称 + 创建 |
| 导入 .db 文件 | 调系统文件选择器（SAF），导入后自动切过去 |
| 导出当前数据库 | 调系统保存对话框（SAF），可存到下载/网盘等用户目录 |
| 删除当前数据库 | 需确认；同时删除 `<name>_bak*.db` 备份 |

导入/导出均用 `ACTION_OPEN_DOCUMENT` / `ACTION_CREATE_DOCUMENT`，**无需存储权限**；
导入时自动去除 `.db` 后缀并在重名时改名（`name-2`），导出文件名保持 `<库名>.db`。

> **删除的顺序很重要**：`YXDBStorage::flush()` 在文件不存在时会**重新创建**它
> （`if (!dirty && std::filesystem::exists(filePath)) return;`），而切换数据库时旧库的析构会
> flush 一次。所以删除当前库时必须**先切走（或先 detach）、再删文件**，否则刚删掉的文件会
> 立刻被写回来（这就是之前“点了删除但没删掉”的原因）。
> 若删的是唯一一个库，则会 detach → 删除 → 重开一个空库（界面提示“已清空（唯一数据库）”）。

### 插件方法

| 方法 | 参数 | 返回 |
| --- | --- | --- |
| `listDatabases()` | — | `{mode, current, name, path, recordCount, databases[]}` |
| `createDatabase({name})` | 名称（不含 `.db`） | 同上 |
| `switchDatabase({name})` | 名称 | 同上 |
| `deleteDatabase({name})` | 名称（默认库不可删） | 同上 |
| `setDatabaseMode({mode})` | `on` \| `readonly` \| `off` | 同上 |
| `getDatabaseStatus()` | — | 同上 |
| `exportDatabase({name?})` | 库名（缺省当前库） | 系统保存对话框 |
| `importDatabase({name?})` | 可选库名 | 系统文件选择器，导入后切过去 |
| `getEngineStatus()` | — | `{evaluator, weight, nnueAvailable, configPath, assets[]}` |

模式 → 引擎命令：`on` → `INFO DATABASE_READONLY 0` + `INFO USEDATABASE 1`；
`readonly` → `INFO DATABASE_READONLY 1` + `INFO USEDATABASE 1`；`off` → `INFO USEDATABASE 0`。
库名与模式用 SharedPreferences 持久化。库文件在 `filesDir/databases/<name>.db`。

> 引擎在搜索过程中会忽略数据库命令，所以建库/切换/改模式都经 `runWhenIdle()`
> 延到搜索结束后执行。

---

## 五、修改边界

本模块只动这些：

- `src/main/assets/rapfi/**`（权重包：`config.toml` + `classical220723.toml` + `classical210901.toml` + 两个 classic 模型 + 4 个 NNUE 权重）
- `engine/rapfi/**`（`RapfiAssets`、`RapfiJni`、`RapfiProtocol`、`NativeRapfiPlugin`、
  `JniRapfiNativeBridge`、本文档）
- `src/main/cpp/rapfi/Rapfi/` 6 个引擎文件（`[database]` 配置 + `min_write_depth`）
- `app/build.gradle`（`externalNativeBuild.cmake` + `androidResources.noCompress`）
- `app/src/main/assets/capacitor.plugins.json`（注册 `NativeRapfi`）
- `engine/RapfiEngineAdapter.java`（无参构造接入真 bridge）

未改动：`domain/`、`model/`、`viewmodel/`、`data/`、`MainActivity`、`capacitor*.gradle`、
`capacitor-android`、`capacitor-filesystem`、`assets/public/**`、KataGo 相关文件。

## 六、注意事项

1. **APK 体积**：NNUE 权重约 39 MB，APK 会相应变大。若需精简，可只保留用到的规则权重
   并同步删掉 `config.toml` 里对应的 `[[model.evaluator.weights]]` 条目
   （**记得同步改 `RapfiAssets.EXPECTED` 清单**）。
2. **插件注册**：`capacitor.plugins.json` 是手工登记的；日后重跑 `cap sync` 要保证仍包含
   `native-rapfi -> NativeRapfiPlugin`。
3. **视角**：`RapfiAnalyzer` 已把引擎输出转成"黑方视角"写入 `AnalysisState`；坐标为零基
   `x/y`，左上角原点、y 向下（与网页一致）。
4. **页面来源**：数据库设置已经是 `web/src/views/Settings.vue` 的正式区块，不依赖运行时脚本注入。
