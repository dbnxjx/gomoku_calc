# 本机运行说明

以下命令适用于本机目录：

```text
JDK: D:\lots_of_app\AndroidStudio\jbr
Android SDK: D:\lots_of_app\AndroidStudioSdk
Android NDK: D:\lots_of_app\android_ndk
```

先在 PowerShell 中设置环境变量。已经在 Android Studio 中配置过环境时，可以跳过已有的设置：

```powershell
$env:JAVA_HOME = 'D:\lots_of_app\AndroidStudio\jbr'
$env:ANDROID_HOME = 'D:\lots_of_app\AndroidStudioSdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:Path"
```

在项目根目录执行完整构建：

```powershell
Set-Location 'E:\university_data\Software_Engineering\gomoku_calc'
.\build-local.ps1
```

脚本会执行以下步骤：

1. 在 `web` 中根据 `package-lock.json` 安装依赖。
2. 用 Vue CLI 编译 `web/src`，生成 `web/dist`。
3. 删除 Android 资源目录中的旧网页 bundle，并复制新的 `web/dist`。
4. 执行 `android\gradlew.bat :app:assembleDebug --no-daemon`。

如果只想启动网页开发服务器：

```powershell
Set-Location 'E:\university_data\Software_Engineering\gomoku_calc\web'
npm ci
$env:NODE_OPTIONS = '--openssl-legacy-provider'
npm run serve
```

Android Debug APK 输出在：

```text
android\app\build\outputs\apk\debug\app-debug.apk
```

安装到已连接的设备：

```powershell
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r android\app\build\outputs\apk\debug\app-debug.apk
```

如果 Gradle 报 SDK 路径错误，在 `android/local.properties` 写入：

```properties
sdk.dir=D:\\lots_of_app\\AndroidStudioSdk
```

`android/local.properties`、Gradle 缓存、`web/node_modules` 和 `web/dist` 都是本机生成内容，不需要提交。
