# ClassBell：超星学习通 Android 助手

课程与作业查看、签到提醒、个人签到结果、课程资料下载。支持 Android 8.0 及以上。

## 下载和安装（新手看这里）

**[下载最新版 v1.3.3 APK](https://github.com/5326AAAA/ClassBell/releases/download/v1.3.3/ClassBell-v1.3.3-debug.apk)** · [打开 Releases 下载页](https://github.com/5326AAAA/ClassBell/releases)

1. 用 Android 手机打开上面的 Releases 页面，进入 **v1.3.3**，展开页面下方的 **Assets（附件）**。
2. 下载文件名以 **`.apk`** 结尾的 `ClassBell-v1.3.3-debug.apk`。APK 是安装包，源码 ZIP 用于查看和开发代码。
3. 下载完成后，在 **夸克、其他浏览器的下载列表，或手机文件管理器的“下载”文件夹**中找到这个 APK，点开后选择 **安装**。
4. 首次安装如需授权，按手机提示允许本次使用的浏览器或文件管理器安装应用，然后返回安装页面继续。
5. 已安装本仓库同签名旧版的用户，直接点 APK 选择 **更新 / 覆盖安装**，即可保留账号、课程和设置。无需先卸载旧版。

安装包约 30 MB。本次提供已签名的 debug 构建，版本为 **1.3.3（47）**。自行编译的 APK 需要使用自己的签名，与本仓库安装包使用不同签名时无法直接覆盖。

## 两个版本怎么选

| 版本 | 体积 | 适合的使用方式 |
| --- | --- | --- |
| [v1.0.0 轻量版](https://github.com/5326AAAA/ClassBell/releases/tag/v1.0.0) | 约 3.2 MB | 体积小，签到需要跳转学习通 |
| [v1.3.3](https://github.com/5326AAAA/ClassBell/releases/tag/v1.3.3) | 约 30 MB | 本机扫码、应用内操作、多个已授权账号分别管理 |

v1.0.0 保留下载；v1.3.3 更新原 v1.2.0 的发布位置。

## 第一次使用

1. 打开“我的 → 账号中心”，登录自己的学习通账号；需要管理其他已授权账号时，在账号中心添加并切换。
2. 在“提醒课程”中选择课程，再到“提醒设置”开启需要的提醒功能。
3. “我的 → 功能权限”按用途展示通知、后台、扫码拍照等权限。展开后可查看锁屏提醒、定时测试和资料下载的要求；相册和文件通过系统选择器授权选中内容。
4. 打开“签到中心”查看当前活动与历史个人结果。扫描当前有效二维码后按页面提示操作；明确提示二维码过期时重新扫描新码。
5. 进入课程可查看作业、签到与课堂活动、资料。资料下载时选择手机上的目标文件夹。

位置签到目前标记为不可用。精确闹钟用于 10 秒定时测试；自启动、横幅和锁屏显示需要按手机系统设置核对。

## v1.3.3 更新

- 优化扫码后提交、明确过期后重扫及多个账号之间的结果隔离。
- “我的”按功能分类展示权限状态；收起时适配一屏，账号卡随可用高度调整。
- 提醒设置、更多工具和单行版本 / 作者固定在底部，展开权限时仍可见。
- 使用帮助顶部加入醒目的 GitHub 仓库入口和 Star 星标操作说明。
- 提供 USB / ADB 调试脚本及独立真机测试，方便开发排查问题。

本轮完成 443 项完整回归、最后微调后的 10 项布局检查和 9 项真机隔离测试。隔离测试使用合成账号和本机 TLS 响应，不代表学校网络的实际签到验收。技术记录见 [真机测试摘要](docs/device-qr-validation.md)。

## 觉得好用，欢迎点个 Star

**这对我很重要，感谢支持！** 打开 [GitHub 仓库](https://github.com/5326AAAA/ClassBell)，登录 GitHub，找到 **Star ☆** 按钮并点击，让星星亮起来，就是给项目加星标了。

## 源码和开发

[下载 v1.3.3 完整源码 ZIP](https://github.com/5326AAAA/ClassBell/releases/download/v1.3.3/ClassBell-v1.3.3-source.zip)。包含应用源码、测试、构建配置、Gradle Wrapper 和调试脚本；不包含本机账号、签名私钥、私有日志、缓存或学习通拆包文件。

构建需要 JDK 17 或 21、Android SDK Platform 35、Build Tools 35.0.0、Go 1.24+。设置 `ANDROID_HOME`，并通过 `-PclassbellGoHome` 指向 Go 安装目录。首次运行会下载 Gradle 和依赖。

```powershell
.\gradlew.bat -PclassbellGoHome="D:/program/go" :app:testDebugUnitTest :app:assembleDebug
```

```sh
sh ./gradlew -PclassbellGoHome=/path/to/go :app:testDebugUnitTest :app:assembleDebug
```

Debug APK 输出到 `app/build/outputs/apk/debug/`。Release 构建使用 `:app:assembleRelease`，发行前需自行签名。按架构拆分可加 `-PclassbellAbiSplits=true`。

USB 调试见 [调试说明](docs/codex-usb-debug.md)；调试桥仅在 debug 构建中启用，release 不包含该组件。第三方组件许可见 `app/src/main/assets/licenses/`。

[学习通官方下载](https://app.chaoxing.com/)
