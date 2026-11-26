# ClassBell v1.5.0 测试记录（2026-11-26）

## 本版功能

作业卡片新增「题目文字与图片」原生入口。可复制全部题目文字或单题文字，并按需将整份/单题配图保存到系统相册 Pictures/ClassBell。包含选择题选项、填空数量、判断和简答正文、共同题干、常见公式文字与图片编号。打开页面只读取 HTML，点击保存后才下载题图。文档附件不处理。

## 执行结果

- 完整单元与 Robolectric 回归：511 项，失败 0、错误 0、跳过 0，72 个测试类。
- 本功能新增 48 项：题目解析 9、详情读取 16、相册保存 20、原生交互 3，全部通过。
- Android lint：0 个错误、182 个警告；APK 构建成功。
- APK 版本 1.5.0（50），证书 SHA-256 与既有 1.4.1 一致，可用同签名安装包覆盖安装。
- 原生页面截图已检查：outputs/ui-v1.5.0/作业题目-复制与题图.png。

首轮针对性 43 项通过后，补充跨官方主机正常跳转、登录词汇误判、MathML、真实图片 attachment / application/octet-stream 响应等兼容情况，再运行上述完整回归。最终统计以完整运行的 XML 报告为准。

## 验证内容

1. 十道选择题与填空、判断、简答混合页面按顺序导出；选项、共同题干、段落、表格、上下标、TeX 和常见 MathML 保留为文字。
2. 整份和单题复制使用对应的图片编号，图片按出现顺序去重；不将图片签名 URL、隐藏表单、已填答案、参考解析或网页导航复制出去。
3. 原生入口自动读取页面一次，复制文字不触发图片保存；点击单题/整份保存时传入准确的图片列表。取消后可以重新读取；切换账号后旧页面不能继续复制或保存。
4. 详情读取固定账号 Cookie 快照，保留现有作业 URL 参数，跨白名单官方主机逐跳校验 Cookie 和 Referer；验证循环、跳转上限、登录/验证页面、异常 MIME、页面体积和取消。
5. PNG/JPEG/GIF/WebP 及相同格式 base64 图片；真实图片的 attachment/octet-stream 兼容、非图片和超限内容拒绝；每批最多 300 张、每张最多 12MiB。
6. Android 10+ 的 MediaStore 待发布项写入、成功发布与失败删除；Android 8/9 的显式写权限、旧版相册登记与失败清理。单图失败后继续其他图片；取消或账号失效时清理当前未完成项。

完整回归同时覆盖原有扫码、照片、多个账号、结果查询、课程资料、提醒、布局和调试等已有测试。本轮没有请求真实学校服务器、运行第三方参考脚本或操作手机。题目页面和相册测试采用合成 HTML、网络响应及 Provider；真实作业页面格式尚待后续设备核查，不能把合成通过写成学校服务端验收。

## 参考依据

静态交叉检索 D:/study/xxt 的相关源码、Python 字节码与官方 Java 拆包。题目结构取自 Helper 和 Doraemon 的 questionLi / mark_name / answerBg 等选择器；官方作业列表 HomeworkInfo.dotaskurl 为现有详情入口依据。未把没有真实响应模型的路径当成已经可用的题目 API。详细使用和限制见 homework-export-1.5.0.md。

## 可复核命令与产物

构建任务：:app:testDebugUnitTest :app:lintDebug :app:assembleDebug（离线缓存，JDK 21、Android SDK 35）。

原始测试报告：app/build/test-results/testDebugUnitTest/TEST-*.xml；lint：app/build/reports/lint-results-debug.xml。安装包和经过隐私清理的源码包在 outputs/ClassBell-v1.5.0-debug.apk 与 outputs/ClassBell-v1.5.0-source.zip。源码包含新增模块、测试、使用文档、此记录、Gradle Wrapper 和已有公开文件；不包含本机账号、Cookie、签名私钥、逆向项目、原始用户日志、构建缓存或本机工具配置。
