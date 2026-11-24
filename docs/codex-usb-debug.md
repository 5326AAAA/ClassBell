# ClassBell USB / ADB 调试

适用版本：1.3.1（45）及以后的 **debug 安装包**。1.3.2（46）已在荣耀 Magic8 / Android 17 上连接并执行隔离仪器测试，详细结果见随附的《真机调试记录-2026-11-24》。

## 能做什么

安装开发包并在手机允许这台电脑的 USB 调试后，Codex 可以通过 ADB 启动应用、操作界面、读取本应用日志、查看权限及后台状态、分析内存与渲染耗时，也可通过 Android Studio 附加调试器设置断点。

本次增加的应用调试桥提供结构化状态快照。电脑脚本会收集：

- 应用版本、进程 PID / UID、设备 Android 版本和型号；
- 摄像头、定位、通知权限及通知监听、电池优化、精确闹钟状态；
- 已登录与监测开关、选课数量，不包含账号名称、账号 ID、密码或 Cookie；
- 当前网络类型与系统是否认为网络有效，不包含 IP、Wi-Fi 名称或请求凭据；
- 本进程堆内存、设备可用内存和应用所在磁盘可用空间；
- ClassBell 已有的脱敏诊断日志，以及本应用的 `dumpsys meminfo`、`gfxinfo` 和进程退出记录。

已有日志包含课程、作业、活动及资料名称。电脑上的诊断目录只保存本次选定的报告，请按实际需要分享。

## 权限层级

| 层级 | 作用 |
| --- | --- |
| 普通 Windows 用户 | 通常可运行 ADB、安装开发包及调试本应用；无需自动提升电脑权限 |
| 手机授权的 ADB shell | 运行设备 shell、收集系统允许的诊断，通过 `run-as cn.classbell.app` 读取本应用的开发数据 |
| 手机 Root / 系统工程镜像 | 由设备本身决定，不会由本调试桥提供；不自动 Root、解锁 Bootloader 或改变系统权限 |

开发包允许调试器附加；应用程序本身不授予 Root 权限。LLDB / 原生库断点还需要对应工具链及调试符号。设备底层的系统跟踪可使用 Android Studio Profiler / Perfetto，支持范围由系统和设备决定。

## 首次连接

1. 手机打开“开发者选项 → USB 调试”。用支持数据传输的数据线连接电脑。
2. 解锁手机，核对并允许这台电脑的 USB 调试请求。Windows 防火墙提示与手机的授权提示是两件事。
3. 运行 Android SDK Platform Tools 内的 `adb devices -l`，设备状态应为 `device`。`unauthorized` 需要在手机确认；列表为空时检查线缆、USB 模式和驱动。
4. 安装本次 debug APK。脚本会用 `run-as` 检查当前安装是否允许应用调试。

本项目电脑的 ADB 在 `D:\program\android\sdk\platform-tools\adb.exe`。脚本同时支持从 `ANDROID_HOME` / `ANDROID_SDK_ROOT` / PATH 查找，也可传 `-AdbPath`。未连接设备时只会给出连接提示。

## 电脑端操作

在项目目录打开 PowerShell：

```powershell
# 读取 JSON 状态，适合 Codex 直接解析
.\scripts\codex-device.ps1 -Action Status

# 保存到新的指定目录，不覆盖旧记录
.\scripts\codex-device.ps1 -Action Collect -OutputDirectory 'D:\study\ClassBell-debug-01'

# 打开应用
.\scripts\codex-device.ps1 -Action Open

# 多台设备时明确指定一台
.\scripts\codex-device.ps1 -Action Status -Serial '你的设备序列号'
```

配套交付目录中的 `codex-device.ps1` 也可以直接运行，脚本不依赖项目文件夹。`Collect` 会先校验本次随机请求 ID，再读取状态，避免拿到上次留下的快照。缺失的旧日志或设备不支持的系统报告会提示并跳过；状态读取失败则停止，不会把旧记录当作本次结果。

## 断点及更深入的应用调试

用 Android Studio 打开源码、选择连接设备，再执行 **Attach debugger to Android process**，选择 `cn.classbell.app`。普通 Java / Kotlin 断点不需要给手机 Root。已有扫码耗时日志包括 `QrTiming` 的调度、真正发送请求头及结果耗时。

本应用的日志位于 `files/diagnostics/`；调试桥的固定输出为 `files/codex-debug/status.json`。可通过 `adb exec-out run-as cn.classbell.app cat files/codex-debug/status.json` 查看最近一次快照。需要新快照时优先使用脚本。

## 接口约束及发布隔离

组件：`cn.classbell.app/.CodexDebugReceiver`。

- 仅接受动作 `cn.classbell.app.debug.COMMAND`，操作 `status` / `ping`。
- 必须提供 32 位小写十六进制 `requestId`。
- 接收器要求发送者持有平台 `android.permission.DUMP` 权限。手机已授权的 ADB shell 可用，普通第三方应用不能通过该接口读取状态。
- 输出路径固定，使用原子写入；不接受脚本、路径、URL、账号或签到操作参数。
- 不启动 HTTP / TCP 服务，不自动开启无线调试，不改变手机的开发者设置。
- 接收器代码和清单位于 `app/src/debug/`；release 构建不包含这个类和组件。

## 荣耀手机：接口正常但 ADB 列表为空

本轮实际遇到 `USB\VID_339B&PID_107D&MI_02` 的 WinUSB 接口状态正常，但只登记了厂商 GUID `{8a4d20a8-a2fa-4495-bee2-e87ece4a5356}`，没有 Google ADB GUID `{f72fe0d4-cbcb-407d-8814-9ed673d0dd6b}`。这是电脑端枚举配置问题，反复切换手机开关不能补上缺失的接口登记。

配套 `scripts/fix-honor-adb.ps1` 是本轮已实际使用的修复逻辑。请将它放在本机私有目录，以 Windows 管理员 PowerShell 运行。它只处理当前唯一一个匹配的荣耀 ADB 接口，校验标准 ADB 类，备份原注册表、保留厂商 GUID、追加 Google GUID，并重启这一个接口。不关闭系统安全设置、不替换驱动文件、不操作手机 Root。

脚本会在自身目录生成回滚备份，包含设备实例信息，不要上传这些备份。`-Rollback` 可恢复原 `DeviceInterfaceGUIDs`。换 USB 口可能产生新实例；备份不匹配时脚本会拒绝修改，需在新的私有目录单独处理。

## 复现隔离的真机二维码测试

测试不要求教师创建课程或签到，也不要求真实账号登录。安装目标 debug APK 和配套测试 APK后：

```powershell
adb shell am instrument -w -r -e disableAnalytics true -e class cn.classbell.app.DeviceQrActivityTest,cn.classbell.app.DeviceQrPipelineTest,cn.classbell.app.DeviceQrEngineTest cn.classbell.app.test/androidx.test.runner.AndroidJUnitRunner
```

测试覆盖真实页面的自动重扫、账号准备失败隔离，以及手机本机 TLS 服务器上的十账号并发。二维码和服务器响应是合成数据，真实摄像头扫码输入由测试结果替代；ML Kit 引擎另在真机处理合成位图。不会向学习通提交合成签到。

每个测试使用独立账本与内存账号仓库，结束后清理测试偏好文件，并断言真实应用 settings 未被改变。测量值同时写入 `am instrument` 输出，避免仅依赖可能轮换的 logcat。真实课堂网络与教师二维码仍需在实际活动中验收。

测试后可运行 `adb uninstall cn.classbell.app.test` 移除测试组件，保留正常应用。要从源码生成测试 APK：`gradlew :app:assembleDebugAndroidTest`。

## 官方资料

- [Android Debug Bridge 与设备授权](https://developer.android.com/tools/adb)
- [按 debug / release source set 隔离代码](https://developer.android.com/build/build-variants)
- [BroadcastReceiver 的发送者权限要求](https://developer.android.com/guide/topics/manifest/receiver-element)
- [Android 平台 DUMP 权限](https://developer.android.com/reference/android/Manifest.permission#DUMP)
