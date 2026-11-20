# ClassBell：超星学习通 Android 助手

ClassBell 是一款面向超星学习通（学习通、Chaoxing、Xuexitong）用户的 Kotlin Android 应用。

- 课程提醒、作业提醒与签到提醒
- 课程和作业查询，个人签到结果查看
- 课程资料下载，支持受限资料下载
- 学习通通知提醒



## 1.0.0

版本代码 41。课程详情默认显示已结束的签到，每条记录可查看个人结果。点击「更新签到与课堂活动」会刷新当前课程，并在下方显示进行中的签到与课堂活动。实际可查询的记录以当前账号能读取的数据为准。

## 构建

安装 JDK 17 或 21、Android SDK Platform 35 和 Build Tools 35.0.0。通过 Android Studio 打开项目根目录，或把 `ANDROID_HOME` 指向 Android SDK。

```sh
sh ./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

Windows PowerShell 使用 `.\gradlew.bat`。首次运行会下载 Gradle 8.11.1 和构建依赖。Release APK 在 `app/build/outputs/apk/release/app-release-unsigned.apk`，安装和发行前请用自己的密钥签名。

第三方组件许可文件位于 `app/src/main/assets/licenses/`。
