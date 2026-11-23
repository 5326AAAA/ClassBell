# ClassBell：超星学习通 Android 助手

课程、作业与签到提醒，课程和作业查询，个人签到结果查看，课程资料下载。

## 下载与版本选择

| 版本 | 体积 | 使用方式 |
| --- | --- | --- |
| [v1.0.0 轻量版](https://github.com/5326AAAA/ClassBell/releases/tag/v1.0.0) | 约 3.2 MB | 体积小，签到需跳转学习通 |
| [v1.2.0](https://github.com/5326AAAA/ClassBell/releases/tag/v1.2.0) | 约 30 MB | 多设备同时登录、应用内直接签到、多账号与代签扫码签到 |

[下载 v1.2.0 APK](https://github.com/5326AAAA/ClassBell/releases/download/v1.2.0/ClassBell-v1.2.0-debug.apk)。Android 8.0 及以上，版本代码 43。与本仓库 v1.0.0 安装包签名相同，可直接覆盖安装并保留账号、课程和设置。

## v1.2.0 功能

- 多个手机同时登录同一个学习通账号。
- 应用内核对课程和账号、提交签到、查看结果，无需跳转学习通。
- 多账号操作、代签扫码签到，分别展示账号身份与操作结果。
- CameraX + ML Kit 本机扫码，兼容 ZXing；支持设备可点击对焦、双指缩放，跳过无关二维码继续识别。
- 相册裁剪、EXIF 方向处理、取景引导、手电筒和停止扫码。
- 课程历史签到结果、签到与课堂活动更新、作业和通知提醒、资料下载。

通用 APK 包含四种处理器架构的原生库及离线扫码模型，体积约 30 MB。本次附件为 debug 构建，保留原文件名。

## 构建

安装 JDK 17 或 21、Android SDK Platform 35、Build Tools 35.0.0 和 Go 1.24 或以上。

将 ANDROID_HOME 指向 Android SDK。将 Go 安装目录通过 Gradle 参数 -PclassbellGoHome 指定，或设置 GOROOT，也可安装到项目的 .tools/go。构建会从 app/src/main/go 编译本地扫码统计 WASM 并自动打包，不需要预先生成文件。

Windows PowerShell 示例（将路径替换为本机 Go 安装目录）：

```powershell
.\gradlew.bat -PclassbellGoHome="D:/program/go" :app:testDebugUnitTest :app:assembleDebug
```

macOS / Linux 示例：

```sh
sh ./gradlew -PclassbellGoHome=/path/to/go :app:testDebugUnitTest :app:assembleDebug
```

首次运行会下载 Gradle 8.11.1 和依赖。Debug APK 位于 app/build/outputs/apk/debug/。可用 -PclassbellAbiSplits=true 生成按处理器架构拆分的 APK。Release 构建使用 :app:assembleRelease，发行前请使用自己的密钥签名。

Go 和 JavaScript 单独测试：

```sh
cd app/src/main/go && go test ./...
node app/src/test/web/classbell-scan.test.cjs
```

Go 测试需在项目根目录执行 JavaScript 测试前返回项目根目录。

## 源码范围

包含应用源码、测试、构建文件和 Gradle Wrapper。未包含本机账号、密钥、日志、构建缓存或学习通拆包文件。第三方组件许可位于 app/src/main/assets/licenses/；Go 构建任务会从所用 Go 安装目录复制其许可文件。

[学习通官方下载](https://app.chaoxing.com/)
