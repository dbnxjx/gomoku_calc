# androidkatago

最小 Android ARM64 demo，用于验证 `KataGomo20250206` 的 Eigen CPU 版本是否能在 Android 上执行。

这个 demo 的按钮分别测试：

- `katago version`：验证 Android 能执行 ARM64 ELF；
- `gtp` + `zhizi_renju28b_s1600.bin.gz`：验证配置读取、权重加载和一次 `genmove B` 推理。APK 里的资源故意命名为 `.bin` 以保持 gzip 字节不被 Android 重新压缩；应用复制到运行目录时改成 `.bin.gz`，这样 KataGo 会选择 gzip 模型读取器。

资源文件：

- `app/src/main/jniLibs/arm64-v8a/libkatago.so`
- `app/src/main/assets/gtp.cfg`
- `app/src/main/assets/zhizi_renju28b_s1600.bin`

在 Windows 上构建：

```powershell
$env:JAVA_HOME = 'D:\lots_of_app\AndroidStudio\jbr'
$gradle = 'C:\Users\zz\.gradle\wrapper\dists\gradle-8.14.3-all\10utluxaxniiv4wxiphsi49nj\gradle-8.14.3\bin\gradle.bat'
& $gradle --offline --no-daemon assembleDebug
```

APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`。由于模型约 270 MB，首次安装和首次模型测试需要较长时间和足够存储空间。

引擎放在 `jniLibs/arm64-v8a` 中运行。Android 的应用 `files/` 目录可能挂载为 `noexec`，直接从那里执行 ELF 会报 `error=13 Permission denied`；因此不要把 `katago` 手工复制到 `files/` 后启动。
