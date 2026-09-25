# androidkatago

这是 `KataGomo20250206` 的 Android ARM64 demo，使用 Eigen CPU 后端运行 Renju（五子棋）模型 `zhizi_renju28b_s1600.bin`。APK 当前默认配置为：

```text
numSearchThreads = 8
numEigenThreadsPerModel = 4
nnMaxBatchSize = 8
maxVisits = 32
maxTime = 30.0
```

模型约 270 MB，第一次启动会把模型复制到应用私有目录。引擎本体从 `nativeLibraryDir/libkatago.so` 启动；不要把 ELF 复制到 `files/` 后执行，因为该目录可能是 `noexec`，会产生 `error=13 Permission denied`。

## 构建

在 Windows 上：

```powershell
$env:JAVA_HOME = 'D:\lots_of_app\AndroidStudio\jbr'
$gradle = 'C:\Users\zz\.gradle\wrapper\dists\gradle-8.14.3-all\10utluxaxniiv4wxiphsi49nj\gradle-8.14.3\bin\gradle.bat'
& $gradle --offline --no-daemon assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。

主要资源：

- `app/src/main/jniLibs/arm64-v8a/libkatago.so`
- `app/src/main/assets/gtp.cfg`
- `app/src/main/assets/zhizi_renju28b_s1600.bin`

## 界面操作和 GTP 命令

启动按钮创建一个长驻的 GTP 进程，并依次发送：

```text
protocol_version
name
version
boardsize 15
clear_board
```

启动成功时，输出区域会看到类似内容。每个 GTP 响应以空行结束：

```text
2026-09-25 ...: Using 8 CPU thread(s) for search
2026-09-25 ...: Eigen (CPU) backend thread 0: Model version 102
2026-09-25 ...: Eigen (CPU) backend thread 1: Model version 102
2026-09-25 ...: GTP ready, beginning main protocol loop
= 2

= KataGo

= 1.12.4

=

=

```

界面按钮对应的命令和典型输出如下：

| 操作 | 发送命令 | 成功响应 | 失败响应 |
| --- | --- | --- | --- |
| 显示版本 | `version` | `= 1.12.4` | `? ...` |
| 当前盘面追加一手 | `play B H8` | `=` | `? illegal move` |
| 替换整盘 | `set_position B H8 W G7` | `=` | `? ...` |
| 清空 | `clear_board` | `=` | `? ...` |
| 悔棋 | `undo` | `=` | `? cannot undo` |
| 显示棋盘 | `showboard` | `= ...棋盘文本...` | `? ...` |
| 生成一手 | `genmove B` | `= H8`、`= resign` 或 `= pass` | `? ...` |

### PLAY：当前盘面追加

在 PLAY 输入框填写 `B H8`，或填写完整的 `play B H8`，点击 **PLAY**。它只发送一条 `play`，不会清空当前局面，也不会重放之前的手数。

```text
play B H8

=

```

如果坐标已有棋子、违反 Renju 规则或颜色/坐标格式错误，引擎返回错误，当前盘面保持不变。

### SET_POSITION：替换整盘

指定盘面输入框只用于替换整盘。支持：

```text
set_position B H8 W G7
```

也支持 15 行、每行 15 个字符的盘面文本：`B` 为黑子，`W` 为白子，`.`、`0`、`_`、`-` 为空位；第一行是第 15 行，最后一行是第 1 行；列坐标为 `A-H,J-P`（跳过 I）。应用时 demo 将其转换成一条原生 `set_position` 命令。

`set_position` 是原子替换，不保留此前的落子历史；空输入等价于 `clear_board`。典型响应：

```text
=

```

重复坐标、非法坐标或不合法的最终盘面会返回 `? ...`，并保留原盘面。

应用盘面后，在上方选择 `B` 或 `W`，即可执行 `GENMOVE` 或开始流式分析。

### GENMOVE、悔棋和清空

`GENMOVE` 发送 `genmove B` 或 `genmove W`。成功后引擎会返回落子坐标，并把该手加入当前盘面：

```text
= H8

```

`悔棋` 发送 `undo`，只撤销最近一手；`清空` 发送 `clear_board`，重置为空盘。

## 流式分析输出

开始流式分析时，demo 发送：

```text
kata-analyze B 10 maxmoves 20 pvVisits true
```

其中 `10` 是 10 centiseconds（100 ms）的报告间隔，`maxmoves 20` 限制每次报告最多 20 个候选点，`pvVisits true` 请求 PV 中每一步的访问量。分析开始会先返回一个 GTP 成功头，之后持续输出 `info`，直到点击停止分析：

```text
=

info move H8 visits 24 utility 0.031 winrate 0.516 scoreMean 0 scoreStdev 0 scoreLead 0 prior 0.210 lcb 0.492 utilityLcb -0.012 weight 24 order 0 pv H8 G7 J8 pvVisits 24 12 5

info move G7 visits 18 utility 0.010 winrate 0.505 scoreMean 0 scoreStdev 0 scoreLead 0 prior 0.180 lcb 0.480 utilityLcb -0.020 weight 18 order 1 pv G7 H8 pvVisits 18 9
```

当前 Renju 分支仍输出 `scoreMean`、`scoreLead` 等 KataGo 兼容字段，但五子棋没有围棋的领地分数；应用界面只使用 `move`、`visits` 和 `winrate`，不会把 `scoreLead` 显示为真实领先分。`winrate` 是 `[0,1]` 的浮点数，`visits` 是整数，`pv` 是后续变化，`pvVisits` 是对应 PV 节点访问量。消费者应按键名解析，不要依赖字段顺序。

点击停止时发送：

```text
stop

=

```

分析期间，demo 会阻止 `PLAY`、`set_position`、`undo`、`clear_board` 和 `GENMOVE`，先停止分析再修改盘面。

## 修改线程数和 visits

### 修改配置后启动

编辑 `app/src/main/assets/gtp.cfg` 后，以下配置只在引擎启动时读取：

- `numSearchThreads`：搜索线程数；
- `numEigenThreadsPerModel`：每个 Eigen 模型实例的后端线程数；
- `nnMaxBatchSize`：神经网络批大小；
- `maxVisits`、`maxPlayouts`、`maxTime`：默认搜索限制。

因此，修改 `gtp.cfg` 后需要停止并重新启动引擎，旧进程不会自动读取新文件。尤其是 `numEigenThreadsPerModel`，它决定模型后端线程池，必须重启才能改变。

### 运行中的 GTP 参数

引擎也支持在同一个 GTP 会话中修改部分参数，不需要重启：

```text
kata-list-params
kata-get-param maxVisits
kata-set-param numSearchThreads 8
kata-set-param maxVisits 64
```

典型响应：

```text
= playoutDoublingAdvantage analysisWideRootNoise maxVisits maxPlayouts maxTime

= 32

=

```

上面的 `kata-get-param maxVisits` 会返回当前值；本分支的 `kata-list-params` 和 `kata-get-param` 没有提供 `numSearchThreads` 的读取接口，但 `kata-set-param numSearchThreads N` 可以直接设置它。`kata-set-param` 会清理当前搜索树，使下一次搜索使用新值。修改 `numSearchThreads` 只改变搜索线程参数，不会改变已经创建的 Eigen 后端线程池；如果还要改变 `numEigenThreadsPerModel`，仍需重启。分析正在运行时，应先发送 `stop`，再发送这些参数命令。

当前 Android 界面没有通用 GTP 命令输入框。只使用界面按钮时，直接修改 `gtp.cfg`，然后停止并重新启动引擎即可；若宿主程序提供 GTP 输入通道，则可以在同一会话中发送上述 `kata-set-param` 命令。
