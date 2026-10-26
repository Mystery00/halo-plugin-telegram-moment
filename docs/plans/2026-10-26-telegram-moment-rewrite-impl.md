# Telegram Moment 重写实施计划

> 执行方式：使用 executing-plans 在当前会话逐项实施，使用 test-driven-development 验证行为，最后独立审查。用户已确认方案并要求直接执行；不重复请求计划批准。

**目标：** 在真实 Halo 中可靠地将 Telegram 消息发布为瞬间。

**架构：** Telegram HTTP 适配器、消息标准化、Halo 持久化收件箱、串行发布工作者、瞬间与附件适配器分离。控制台只管理状态、配置入口、重试和脱敏日志。

**技术栈：** Java 21、Halo API 2.22.11、Vue 3、Rsbuild、JUnit 5；WSL Docker 构建与运行。

**设计：** [重写设计](2026-10-26-telegram-moment-rewrite-design.md)。

## 全局约束

- Halo >=2.22.11，瞬间 >=1.15.0；保留插件 ID、配置键和旧注解迁移。
- 中文界面/注释/文档，英文日志，Token 不进入日志或报告。
- 保留现有锁文件修改；合并重复依赖键，不丢失瞬间依赖意图。
- 在 `rewrite/telegram-moment` 分支当前工作区实施，不自动提交、推送或发布。
- 所有计划、决策和验证记录在 `docs/plans`，真实 Token 等用户在可见浏览器输入。

## 重点反例

1. 相册首条无 caption，后来成员含屏蔽标签：整组不得漏发，任务 2/3 验证。
2. Moment 已提交但收件箱未更新就重启：不得重复创建，任务 3/4 验证确定名称与提交标记。
3. 编辑时管理员同时改变无关 metadata/status：乐观锁重试并保留字段，任务 4 验证。
4. 旧 Bot 的延迟回复/工作者遇到配置切换：不得向新 Bot 串发，任务 5 验证。
5. 服务中断或错误文本包含 Token：状态与日志都不能泄露，任务 1/5/6 验证。

## Task 1：配置、内容与消息边界

文件：`config/TelegramSetting.java`、`publisher/ContentBuilder.java`、`telegram/IncomingMessage.java`、`telegram/MessageParser.java` 和对应测试。

接口：`TelegramSetting.from(ConfigMap)`；`validate()`；`fingerprint()`；`ContentBuilder.build(String, JsonNode)`；`MessageParser.parse(JsonNode, TelegramSetting)` 返回可接受消息或空。

- [x] 写并运行失败测试：无 from 频道、群聊拒绝、非法 ID 不放行、私聊必须白名单、精确标签、嵌套实体、emoji、HTML 与链接转义。
- [x] 实现配置及标准化 DTO，限制媒体 20 MiB，文本 65536 字符，实体 256 个，配置指纹不暴露 Token。
- [x] Docker `gradle test -x processUiResources`，上述测试通过。

## Task 2：Telegram 传输

文件：`telegram/TelegramApi.java`、`telegram/TelegramApiException.java` 及 HTTP 服务测试。

接口：`getMe()`、`updates(long)`、`download(String)`、`reply(...)`、`deleteReply(...)`、`close()`。

- [ ] 假 HTTP 服务验证请求方法、统一端点、文件路径、错误 Token、429 和 Token 脱敏；先观察失败。
- [x] 实现 Java HttpClient：轮询 20 秒、请求 30 秒、文件 60 秒、响应上限，限量缓冲后写临时文件，关闭后取消请求。
- [x] Docker 运行适配器测试通过（统一端点、文件路径、429 和敏感错误描述脱敏）。

## Task 3：持久化收件箱和相册

文件：`sync/SyncRecord.java`、`sync/BotCursor.java`、`sync/SyncStore.java`、`sync/SyncCoordinator.java` 和测试。

接口：`accept(long botId, JsonNode update, TelegramSetting)` 先保存记录再推进游标；`ready(botId)`；`retry(name)`；记录存成员、版本、重试时间、Moment 名称和附件映射。

- [ ] 测试重复 Update、相册排序与任意成员编辑、先持久化后 offset、重启恢复和旧版本不覆盖新版本。
- [x] 实现独立 Scheme、Bot ID 隔离和相册防抖，待处理/失败上限 1000，最多 5 次自动尝试，失败可手动重试。第 1–4 次失败后分别等待 2/4/8/16 秒，第 5 次进入失败状态；429 尊重服务端等待时间。
- [x] 保存已完成映射，仅持久化标准化成员，不保存完整原始 Update；保留相册编辑所需成员。
- [x] Docker 测试通过。

## Task 4：Halo 瞬间和附件集成

文件：`publisher/MomentPublisher.java`、`publisher/AttachmentUploader.java`、`publisher/LocalFilePart.java` 及测试；删除第三方 stub。

接口：`publish(SyncRecord, TelegramSetting, TelegramApi)` 返回提交结果；`delete(SyncRecord)`；附件使用显式作者的 `AttachmentService.upload`。

- [ ] 测试稳定名称、原地更新、保留未知字段、旧注解匹配、附件失败保持旧 Moment、提交后恢复、清理失败可重试。
- [x] 通用扩展对象读取并按最新版本更新，使用命名空间注解标记来源、版本和附件；失败回收新上传附件，删除后清理旧附件。
- [x] Docker 测试通过；真实 Halo 模拟 Bot 集成验证实际附件及瞬间查询。

## Task 5：生命周期、状态和可选回复

文件：`bot/TelegramBotService.java`、`bot/BotController.java`、`LogBuffer.java`、`TelegramMomentPlugin.java`、`extensions/role-template.yaml` 和测试。

接口：`startBot/stopBot/restartBot/status/retry`；状态查询只读内存与配置指纹。轮询和发布独立线程，配置切换前等待旧线程退出。

- [ ] 测试启动失败可见、断线/429退避、无 Token、Token 脱敏、停止、重复重启、旧 Bot 隔离及回复失败不影响发布。
- [x] 启动校验作者、策略与瞬间，注册并在停止时注销自身 Scheme；命令鉴权前置。
- [x] 回复默认关闭、只回复支持的已授权消息，记录并过滤自己的回复 ID；延迟删除随 Bot 生命周期释放。
- [x] Docker 后端全量测试通过。

## Task 6：管理界面、文档与完整验收

文件：`ui/src/views/HomeView.vue`、`ui/src/api.ts`、相关测试、`README.md`、`scripts/` 容器验证脚本、构建文件和 Setting。

- [ ] 先测试状态错误与配置待应用、重试失败反馈，不将网络错误显示为已停止。
- [x] 实现中文状态、配置链接、失败任务重试、脱敏日志和权限控制；前端测试用 run 模式。
- [x] WSL Docker 前端测试/类型检查/构建、后端测试和 JAR 完整打包通过。
- [x] 独立 Halo 2.22.14 + 瞬间 1.15.0 / 1.16.0，模拟 Telegram 全链路通过。
- [x] 可见浏览器检查界面，用户输入真实 Token 后完成真实频道文字、相册、附件及文字编辑验证。
- [x] 独立代码审查，处理重要问题并再次验证；交付 JAR 与中文操作说明，明确真实网络验证状态。

## 执行记录

- 初始基线：`108de0b`；两个原有未提交修改已识别。
- 决策：当前工作区创建专用分支而不另建 worktree，避免漏带用户未提交依赖和锁文件；不自动提交。
- 接口预检：任务 1 DTO → 2 HTTP → 3 持久化 → 4 发布 → 5 生命周期 → 6 UI，均在同一会话依次实现，签名变动同步更新调用与测试。

### 2026-10-26 执行进展

- 任务 1–5 的实现已完成；移除 Telegram SDK、旧内存聚合器、匿名 HTTP 附件上传和第三方 Moment stub。数据层实际命名为 `SyncRepository`，发布层为 `SyncPublisher`、`HaloMediaUploader`。
- 任务 6 管理页、权限声明、中文说明、WSL Docker 构建脚本和模拟集成脚本已完成。前端接口封装位于 `dashboard.ts`。
- 后端 48 项测试、前端 3 项测试、类型检查、前端打包及后端完整 JAR 构建通过。完整构建先在 Node 容器完成 UI，再在 Gradle 容器跳过重复的 Node 任务，仍执行 UI 资源复制与后端测试。
- 真实 Halo 2.22.14 + 瞬间 1.15.0：无 from 文字、编辑身份保持、重复消息、相册说明/成员编辑、整组删除、附件内容读取、屏蔽标签、媒体失败恢复通过。
- 真实 Halo 2.22.14 + 瞬间 1.16.0：上述链路以及 `/redo`、5 次失败后手动重试、Bot 重启恢复、匿名管理访问拒绝通过。
- 浏览器检查发现并修复重试端点参数绑定 500：`BotControllerTest.retryBindsTaskNameFromHttpPath` 先失败后通过；修复后在可见浏览器重试旧失败任务，状态进入待处理。
- 真实附件回调发现不可变注解集合：`HaloMediaUploaderTest.supportsImmutableAnnotationsFromHaloStorage` 先失败后通过。
- 附加回归：重复文件的相册保留两个媒体位置、旧附件替换后的清理、部分 pre 块的有效 HTML 均先失败后通过。
- 独立审查 Important 1 已修复：未知 `/rm` 不落盘中间 PUBLISH 状态；`unknownDeleteNeverPersistsPublishIntermediateState` 先失败后通过。
- 独立审查 Important 2 已修复：新版本失败不能清理旧版本实际引用的 staged 附件；`failedNewRevisionCannotDeletePreviouslyCommittedStagedFile` 先失败后通过。发布和清理以现存 Moment 引用为准，上传归属注解用于找回落盘窗口中的附件。
- 审查小问题：模拟调用记录的类型错误已修复并重跑。暂保留管理页最多 100 条的限制，已写入 README；大量失败任务的分页作为后续改进。
- 决策：媒体响应采用有 20 MiB 硬上限的内存读取后写入临时文件，再交给 Halo 流式上传，以统一取消与超限处理；代价为单工作者额外最多约 20 MiB 响应内存，非无界缓存。
- 未自动提交、推送或修改用户原有运行中的数据库容器。真实 Token 与真实 Telegram 网络仍待用户在可见浏览器输入后验收。
- 两个瞬间版本最后均完整重跑通过手动重试与重启恢复；1.15.0 额外验证公开查询可见性。构建脚本从新依赖卷运行成功。新增附件归属找回与清理失败重试两项测试通过。
- 未勾选的复合测试项代表尚未覆盖其列举的全部故障组合，并非实现未完成；尤其真实 Token、完整旧版生产数据迁移及真实回复发送仍未验收。
- 22:20 更新：用户已在浏览器保存真实 Token；限定测试频道后真实身份和长轮询通过。文字、两张图片相册及文字原位编辑均成功，公开接口可见、图片字节可读，队列与失败数为 0。真实 Token 验收项已完成；私聊及真实可选回复未启用。详见同目录验收记录。
