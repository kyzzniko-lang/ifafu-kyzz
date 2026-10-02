# iFAFU 功能与稳定性审查报告

本次审查覆盖 Android 客户端的登录与会话、网络请求、缓存、课表、考试、成绩、选课、评教、首页、提醒、小组件、更新下载、模拟定位、崩溃记录、宠物、笔记和日历导出。修复均在现有工作区上完成，保留开始审查时已有的未提交改动。

结论：确认的功能误判、旧请求回写、数据覆盖、异常锁死和日期计算问题已落实修复，并增加针对性回归测试。下表记录可确认的触发条件及处理方式。它不代表线上教务系统和所有厂商设备已经完成实测。

## 会话与数据隔离

| 问题与触发条件 | 修复方式 | 主要代码 |
| --- | --- | --- |
| 登录时刷新验证码、重复点击或切换账号，旧挑战仍可能提交或覆盖新账号 | 验证码绑定凭据及挑战世代，登录防重入，最终凭据原子提交 | UserApi、LoginViewModel、UserRepository |
| 改密码期间切号，把原账号新密码存入当前账号，或重复发送修改 | 修改防重入，绑定原会话；成功后在会话锁内保存密码并重登 | PasswordModifyViewModel、UserRepository |
| 退出后再登录同一账号，账号字符串相同，旧请求仍可能回写 | 区分凭据世代与业务会话世代；相同账号自动重登可继续，退出和切号使旧任务失效 | UserRepository、ReloginHelper |
| 网络请求已取消但阻塞执行继续占用全局锁 | 使用可取消 OkHttp 桥接；取消时终止 Call，释放互斥锁 | HtmlClient |
| 旧请求在清除 Cookie 后返回，重新写入旧账号 Cookie | 请求及完整重定向链绑定 Cookie 世代，拒绝旧响应的 Cookie 和正文 | AppModule、HtmlClient |
| Cookie 过期后从旧 CookieStore 回退为永不过期，或重启后丢失 secure、hostOnly 和 path | 保存完整 OkHttp Cookie 属性，按 domain/name/path 识别并清理，按 Cookie.matches 匹配请求 | JavaNetCookieJar |
| WebView 用一个 setCookie 写多个 Cookie，只正确安装第一项，还可能把安全 Cookie 复制到 HTTP | 每项单独设置，并保留服务端完整属性；URL 限定为 HTTP 或 HTTPS | WebActivity |
| JavaScript 字符串、注释、正则或嵌套函数中的 alert 被当成页面报错 | 词法扫描跳过非执行文本并追踪函数作用域 | HtmlClient |
| 首次成绩基线将已出分与未出分都记为零，第二次刷新把旧成绩全部标 NEW | 未出分用独立状态标记；静默基线、首次出分、成绩变更分别处理 | CacheManager |

## 学业功能

| 问题与触发条件 | 修复方式 | 主要代码 |
| --- | --- | --- |
| 成绩表格多出属性、表头使用 td 或页面不包含 footbox，成绩静默缺失或表头混入结果 | GET 与 POST 统一 DOM 解析，识别表头及真实成绩行 | ScoreParser、ScoreApi |
| 查询全部成绩失败，默认学期的部分成绩仍被当成全量成功 | 返回失败，保留原缓存，不提交不完整监控基线 | ScoreApi |
| 等级制补考成绩不能转换为有效分数 | 统一等级分数映射 | ScoreParser |
| 选课表的“选课课号”被识别为复选框列，已选课程混入可选列表 | 用真实表格及 input 控件识别选择列、可选表与已选表 | ElectiveParser、SimpleElectiveParser |
| 选课时会话过期，重登后列表顺序改变，复用旧控件名可能选错课程 | 重载表格后重新定位并核对目标课程，不能确认则安全失败 | ElectiveCourseApi、SimpleElectiveApi |
| 无 alert 的普通错误 HTML 被当成选课成功 | 需要服务端成功提示或已选列表证明 | ElectiveCourseApi |
| 评教把禁止提交的 alert 判为成功，或从全页第一个 selected option 取错课程 | 精确识别评教表单、课程选择和控件 name，明确成功与拒绝提示 | CommentTeacherApi |
| 评教快速切换课程后旧请求覆盖新页面，教师名缺失沿用上一位教师评价 | 切换时清理状态并约束异步结果所属课程 | CommentViewModel |
| 排课去重忽略非连续周集合，保留了相同节次但漏掉其他周次 | 排课身份加入标准化周集合 | Course |
| 课表中的巨大或非法节次引发网格长循环，内容标签干扰行节次 | 校验节次边界，只识别整格节次标签；解码课程实体并保留考试信息 | SyllabusParser |
| 考试按负天数判成未过期，三天后的未来考试反而判过期 | 修正过期判断并复用空列表缓存时效 | ExamViewModel |
| 跨天考试把第一天时间段套到第二天，导致冲突或排序错误 | 每个日期绑定对应场次时段，按最近实际场次排序 | ExamDates |
| 指定学期失败后仍返回默认学期，使页面显示错学期 | 目标学期无法确认时失败并保留原缓存 | SyllabusApi、ExamApi |

## 后台任务与应用生命周期

| 问题与触发条件 | 修复方式 | 主要代码 |
| --- | --- | --- |
| 关闭成绩提醒后已排队 WorkManager 仍联网或通知 | 即时取消唯一任务，执行前后复查偏好、取消状态及会话 | ScoreCheckReceiver、ScoreCheckWorker、SettingsFragment |
| 后台成绩请求期间前台完成刷新，旧后台响应覆盖新成绩并重复通知 | 按账号维护缓存版本，锁内比较请求前版本及原子提交；清缓存也递增版本防止 ABA | CacheManager、ScoreCheckWorker |
| 课前闹钟隔天才到达或切号后到达，推送旧课程 | 闹钟附带日期、账号及会话世代并检查；主页统一使用带偏好守卫的调度入口 | CourseReminderReceiver、MainActivity |
| 首页缓存跨账号复用，旧网络结果覆盖新页面；学期同步覆盖刚设置的手动日期 | 切换会话时取消任务、清空内存日期键；提交前原子校验会话与手动设置 | MainViewModel |
| 手动开学日与标志分两次写入，被后台自动写入插入；系统语言产生非 ASCII 存储日期 | 手动日期与标志原子保存，自动保存同锁复查；持久化格式固定 Locale.US | UserRepository、SettingsFragment、ToolboxViewModel、SyllabusApi |
| 课表刷新失败后恢复上一学期课表，按当前周次冒充今日课程 | 失败恢复路径也校验课表所属学期 | MainViewModel |
| 下载初始化异常后 busy 状态永久不释放，广播遗漏或轮询失败不切镜像 | 统一幂等完成与失败出口，广播及轮询检查真实下载状态，持久化下载信息以便恢复 | UpdateChecker |
| 下载到 HTML 页面或安装权限返回后无法继续 | 校验 APK 包名；主界面及设置页恢复待完成下载和安装 | UpdateChecker、MainActivity、SettingsFragment |
| 模拟定位所有 provider 启动失败仍显示运行，非法坐标或缺失轨迹进入服务 | 拒绝非法启动；校验坐标；无法恢复轨迹时停止；终点速度归零 | MockLocationService |
| ANR 日志轮转删除待反馈崩溃，或同毫秒样本互相覆盖 | 保护待反馈及刚写样本，使用唯一文件名 | CrashReporter |
| 小组件更新异常导致调度断链，删除最后小组件又恢复闹钟；外部可调用自定义广播 | 异常后续链并检查组件存活，receiver 不对外导出 | iFAFUWidget、AndroidManifest |

## 本地数据与日期功能

| 问题与触发条件 | 修复方式 | 主要代码 |
| --- | --- | --- |
| 宠物页按 load、save 分段更新，与番茄钟或设置写入交错，丢失经验或名称 | 所有修改统一使用仓库原子读改写，持久化后再发布界面通知 | PetViewModel、PetRepository |
| Gson 接受 null、空对象或含 null 字段，笔记、宠物及倒计时后续使用时崩溃 | 检查结构、逐项容错；宠物合并构造默认值并规范数值范围 | NoteRepository、PetRepository、CountdownDates |
| 录音尚未 stop 就保存路径，保存后才在销毁时完成文件；重录失败删除上一段草稿 | 保存前完成录音；始终写新文件，成功完成后才清理上一段；失败恢复旧路径 | NoteEditActivity |
| 配置变化或系统后台销毁导致恢复的笔记指向已删除录音 | 保存草稿音频状态，后台重建保留文件，主动退出清理；后台停止录音及播放 | NoteEditActivity |
| 倒计时按毫秒除 24 小时，夏令时边界差一天；宽松解析接受不存在的日期 | 使用 LocalDate 与日历日差，严格解析，首页与列表统一口径 | CountdownDates、CountdownActivity、HomeFragment |
| 日历导出无效开学日期抛异常、缺 UID 与 DTSTAMP、设备时区导致上课时间漂移 | 提取纯导出器，严格日期与节次校验，校园时区转换为 UTC，稳定 UID、完整事件字段 | SyllabusCalendarExporter、CalendarExportActivity |
| 日历中文长行、换行和分隔符破坏事件格式 | CRLF、文本转义及按 UTF-8 字节折行，按真实上课周逐场生成事件 | SyllabusCalendarExporter |

日历格式依据 [RFC 5545](https://www.rfc-editor.org/info/rfc5545/)，包括必填事件字段及内容行规则。小组件广播声明参考 [Android 官方文档](https://developer.android.com/develop/ui/compose/glance/create-app-widget#declare-widget-manifest)。模拟定位权限声明保留，只在该声明上说明并局部排除系统专用权限的 Lint 误报；未整体禁用 Lint。

## 验证与持续检查

统一检查命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
git diff --check
```

GitHub Actions 增加 Android Lint 及报告归档，继续执行单元测试和 Debug APK 构建。

统一验证结果：189 项测试，失败 0、错误 0、跳过 0；Debug APK 构建成功；Android Lint 错误 0、警告 671；`git diff --check` 通过。可安装构建产物位于 `app/build/outputs/apk/debug/app-debug.apk`。测试报告位于 `app/build/reports/tests/testDebugUnitTest/index.html`，Lint 报告位于 `app/build/reports/lint-results-debug.html`。

## 验证边界与后续维护

本机没有连接 Android 设备。Robolectric 与解析样本能验证会话竞态、存储、调度意图、失败恢复和格式规则，不能替代教务网站真实账号操作、真实麦克风、实际下载安装、厂商 ROM 的省电策略、定位权限和桌面小组件实测。未对外提交评教或选课，未发布版本或部署服务。

本轮集中修复客户端；仓库内的 Cloudflare Worker 和网站未作上线验证。教务页面改版仍可能影响解析，应保留去标识化 HTML 样本并持续扩充解析回归用例。

发布前建议在 Android 7、12、14 及一台实际使用的厂商设备上执行：切换账号与同账号重登、网络断开和恢复、学期切换、跨天考试、选课与评教确认、关闭和重开提醒、重启后的小组件、麦克风占用时重录、旋转与后台重建、更新下载及未知来源安装授权返回、日历导入。

Lint 的非阻塞警告包括硬编码文本、未使用资源、国际化、布局及过时 API 等；这些没有在本轮批量改动或整体抑制。它们应按功能风险和维护成本分批处理。
