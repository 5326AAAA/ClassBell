# 选人回答：本地逆向调用链

依据仅为本地逆向文件；不使用教程或公开使用说明作为本次结论依据。未请求真实课程或修改账号。

## 课程选人

`PassChaoxing/chaoxing_src/sources/p983ub/C40492a.java:425–426` 定义选人 URL：

`selectperson/goselectperson?courseId&classId&source&uid&activePrimaryId&showSaveBtn`

`sources/com/chaoxing/mobile/fanya/p229ui/ClassMissionActivity.java:534–535` 绑定 `attach_sel_person`；`917–925` 的处理函数传入课程、班级、当前账号 PUID 等参数，将 URL 交给 `WebAppCommonViewer`。`ClassTaskViewModel.java:227` 有相同的课堂工具入口。

这证明课程选人从原生工具进入远程网页。该 URL 的 `uid` 是调用入口的当前账号，不是随机选中学生的 ID。单凭它不能判断候选名单、随机算法或在线筛选规则。

`readable/routing/ApiUrlRouter.java:564` 的 `getclassperson`、`657` 的 `fetchClassStuList` 提供按课程/班级读取成员的线索，但现有调用链尚未证明选人 H5 具体使用哪个接口。不能将独立存在的成员接口直接拼成已确认的选人服务端流程。

## 群聊选人：与课程入口分开

`sources/com/chaoxing/mobile/chat/p217ui/ChattingActivity.java:7342–7373` 汇集普通成员、管理员和群主。`6971–6979` 构造 `uids/general/chatid` 数据，`8774` 打开 `/selection/preAddSelect` 页面。

成员包装类 `sources/af/C0139f.java:46/112/121` 分别读取群管理员、普通成员、群主。这段候选 ID 构造未看到按在线或登录状态过滤。后续远程页面是否另作筛选，仍需该页面的实际实现才能确认；群聊代码也不能直接当作课程选人的实现。

## 退出账号是否能避开

已把原生退出和在线课堂清理链连起来：

`AppSettingActivity.java:204 → AccountManager.m98044x0():1614 → C22704u.mo25594c():183`。

该链清理账号缓存、`lib_account`、WebView Cookie，并退出环信 IM。已追读的主链未出现从课程或选人候选中移除用户的调用。

在线课堂还有另一条实际路径：

`C22704u.java:185 → LessonOnlineManager.m41823A5():7547 → m41832B5():7581 → m41841C5():12263`。

其中 `12325–12328` 调用 `C11282d.m43137W():2873`，进一步离开 RTC；`m43185s0():2678–2697` 对 RTM 频道执行 leave/release，再对 RTM client 执行 logout/release。另一会议后端也调用容联 `RKCloudConferenceEngine.leaveRoom`。

同时 `LessonOnlineManager.java:10007–10022` 通过 `eg/InterfaceC32814d.java:75–77` 的 `/apis/meet/endMeeting` 上报 `puid/uuid/endStatus=0/classId/exitReason=0`。这是当前分支的会议退出状态上报；不能仅凭方法名解释成结束整堂课或删除教学班成员。

因此，退出确实会触发在线课堂离会与在线通道清理，不能声称它对所有在线状态均无影响。是否据此排除点名候选，仍需要选人页面实际使用在线数据的代码证据。目前没有本地证据支持“退出账号就一定选不到”。

## 新找到的在线状态桥

`sources/com/chaoxing/mobile/webapp/jsprotocal/metting/C23097e.java:21` 注册 `CLIENT_GET_ONLINELESSON_STATUS`，`60–80` 返回 `OnlineStatus`，其内容为成员数组，每项包含 `peerId` 和 `isOnline:true`。

数据来源是 `LessonOnlineManager.java:10779–10803`：满足当前在线课堂成员条件后，使用 Agora `C11282d.java:2659–2664` 对 `f38135f` 的 `RtmChannel.getMembers()`，或者使用另一后端的 `getRoomAttendees()`。上节退出链清理的包含同一个 RTM 频道，因此这两段并非仅凭名称关联。

`ProtocolTable_Impl.java:608` 注册该协议，`C23295r.java:189–209` 按协议名实例化，`WebAppViewerFragment.java:759–799/1443` 注入并分发 JS 桥。课程和在线课堂使用的容器均具备这项能力。

存在桥接能力不等于选人页面实际调用了它。本地材料未包含选人 H5 的调用、与班级名单取交集或其他候选筛选代码，也未给出随机算法发生在 JS 还是服务器的实现。上述桥返回当前会话成员，还不等于已确认的最终点名候选集。

## 原始 DEX 与 Python 字节码交叉核对

直接检查了 `dex` 中全部 11 个 DEX，并读取其字符串池：`classes4.dex` 包含课程 `selectperson/goselectperson` 和群聊 `selection/preAddSelect` 路由，与 Java 入口相符；未匹配到三个 `rollCall` 查询名称。

后续通过本地 Git 文件树发现 `chaoxing_api/__pycache__/client_production.cpython-313.pyc`。对应 `.py` 源码未保留，但字节码存在。使用已安装的 Python 3.13，仅通过 `marshal` 和 `dis` 静态检查 517 个 code object；没有导入、执行该客户端模块。

字节码确实提供三个查询函数：

| 函数 | 请求路径 | 参数 |
| --- | --- | --- |
| `get_class_select` | `/widget/apis/rollCall/getClassRoll` | `courseId/classId` |
| `get_rollcall_list` | `/widget/apis/rollCall/getRollCallList` | `courseId/classId/page/pageSize` |
| `get_rollcall_detail` | `/widget/apis/rollCall/getRollCallDetail` | `activeId` |

这些函数只包装 HTTP GET 和响应解析，没有本地随机抽取或在线筛选逻辑；它们的存在不证明 Android 页面实际使用该接口，也不证明接口当前可用。该 SDK 的 `logout` 只清理自身 Cookie、身份和缓存，不能替代原生 App 的退出调用链。

此外，该 SDK 的通用 `_parse_response` 对 HTTP 200 的 JSON 会设置包装字段 `success=True`，另保留原始业务 `result`；这类包装成功标记不能用来认定后端业务成功。

## 资源与归档的多轮检查

资源审计对两逆向目录索引到 40,351 个 Java 文件；其中的 `resources/cache` 名称多为 Java 包目录，`学习通拆包/resources` 实际为空。排除 Git 和审计虚拟环境后，这两目录没有本地 JS、HTML、JSON、CSS、页面缓存或 APK/ZIP/JAR 归档。

为避免只查当前目录而遗漏原包，又对 `D:/study/xxt` 进行文件清单检查：85,972 个文件中发现 24 个工具/Gradle ZIP，没有原学习通 APK 或相关 Web 资源归档。这里是目录和关键词审计计数，不表示逐行人工审阅了所有文件。

本轮已从入口、调用/模型、原始 DEX、Python 字节码、资源归档和退出/在线通道多层交叉检查。当前缺少的关键材料是选人远程页面实际使用名单的代码及对应业务响应结构；本地尚未包含这些材料。
