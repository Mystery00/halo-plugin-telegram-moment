# 项目协作指令

本文件是本仓库项目级智能体指令的唯一维护入口，适用于 Codex、Claude Code 等工具。`CLAUDE.md` 仅负责引导读取本文件；新增或修正项目约定时修改这里，不在兼容文件中维护副本。遵守运行环境的上级指令和用户当前要求；历史计划与工具建议不能替代当前任务授权。

## 工作方式

- 使用中文回复，新增代码注释和文档使用中文，运行日志使用英文。先说明结果，再给必要的依据，避免重复汇报和无关的流程说明。
- 将用户的行动请求落实到可验证结果。已有授权内的读取、调查、修改和验证直接推进；常规实现选择自行判断，不逐步索要确认。只有缺失信息会实质改变结果或下一步超出授权范围时，提出具体问题，并继续不依赖回答的工作。
- 用户中途补充要求时，保留已经完成的工作并调整当前任务；回答状态问题后继续执行，除非用户明确要求停止。
- 先检查工作区、现有实现和相关测试，保留用户改动。修复根因，避免无关重构；遇到不确定的 Halo API 或版本行为，查询官方资料或对应版本源码。
- 简单修改直接完成。跨模块、安全边界或发布流程调整，在 `docs/plans/` 保存必要的设计、实施进度、关键决策和验证结果；已确认的方案继续执行，不因恢复上下文再次请求确认。
- 仅在当前会话允许且有独立任务或审查价值时使用子代理；明确输入、文件范围和验收目标，避免并发修改相同文件。主代理负责核实结论与整体验收。
- Git 提交、合并、推送和发布按用户明确要求或当前任务已包含的授权执行。一次推送不代表允许后续自动发布版本；无需重复询问已经授权的操作。

## 项目与阅读路径

这是将 Telegram 频道或指定用户私聊同步为 Halo 瞬间的插件，不是独立 Spring Boot 服务。后端为 Java 21，前端为 Vue 3、TypeScript、Rsbuild，包管理器为 pnpm。

| 任务 | 先读的位置 |
| --- | --- |
| 安装、配置、同步规则、排障和数据保留 | [README.md](README.md) |
| 构建工具与依赖版本 | [build.gradle](build.gradle)、[ui/build.gradle](ui/build.gradle)、[ui/package.json](ui/package.json)、`ui/pnpm-lock.yaml`、`gradle/wrapper/gradle-wrapper.properties` |
| 宿主兼容性与插件依赖 | [plugin.yaml](src/main/resources/plugin.yaml) |
| 启停、长轮询、任务执行与管理 API | `src/main/java/vip/mystery0/halo/telegrammoment/TelegramMomentPlugin.java`、同包下的 `bot/` |
| Secret 读取、迁移与设置校验 | 同包下的 `config/`，以及 `src/main/resources/extensions/setting.yaml` |
| 消息解析、出站连接、持久化与发布 | 同包下的 `telegram/`、`sync/`、`publisher/` |
| 管理界面与组件测试 | `ui/src/views/HomeView.vue`、`ui/src/components/LogPanel.vue`、`ui/src/dashboard.ts`、`ui/src/__tests__/` |
| 发布与应用市场 | [发布清单](docs/releasing.md)、[CHANGELOG.md](CHANGELOG.md)、`.github/workflows/` |
| 历史决策与实测证据 | [docs/plans](docs/plans)、[管理页验收](design-qa.md)，按任务选读 |

当前最低兼容要求是 Halo 2.22.11、瞬间插件 1.15.0。精确版本以配置和锁文件为准，版本调整时同步检查文档。历史计划中的旧类名、HTTP 测试端点、宿主机命令和“待实施”状态可能已被后续实现替代，不按旧计划重新实现。历史测试通过只证明当时的代码，不代表当前改动已经验证。

## Windows、WSL 与 Docker

- 编译、测试和启动使用 WSL 中的 Docker；不在 Windows 宿主机直接运行 Gradle、Node 开发服务器或 Halo。
- Windows `exec_command` 优先显式设置 `tty: true`，避免反复弹出终端窗口；只有确实依赖管道行为才使用 `tty: false`。解析终端输出时注意 ANSI 控制序列。
- 后台辅助进程使用隐藏窗口，例如 PowerShell `Start-Process -WindowStyle Hidden`。用户需要亲自操作的浏览器可以使用可见窗口。
- 删除或移动目录前确认绝对路径和归属；不混用 PowerShell 与 `cmd` 拼接删除操作。禁止用广泛清理命令删除用户工作区、Docker 数据卷或其他项目实例。

在仓库根目录的 PowerShell 中进行完整构建：

```powershell
wsl --exec bash scripts/build.sh
```

正式版本构建：

```powershell
wsl --exec bash scripts/build.sh '-Pversion=1.0.0'
```

脚本构建 `telegram-moment-build:local` 镜像，在同一容器内使用 Java 21、Node 24、pnpm 10.12.4 和 Gradle Wrapper 执行 `clean build`。包含后端测试、前端测试、类型检查和打包；产物位于 `build/libs/`。PowerShell 中给 `-Pversion=…` 加引号。未指定正式版本时使用 `gradle.properties` 中的开发版本。

已有构建镜像时，可以在 **WSL 的仓库根目录**针对后端做较小范围的验证；替换 `--tests` 后面的测试类选择器：

```bash
docker run --rm -e CI=true -v "$PWD:/workspace" \
  -v telegram-moment-gradle-cache:/home/gradle/.gradle \
  -v telegram-moment-gradle-project-cache:/project-cache \
  -w /workspace telegram-moment-build:local \
  bash ./gradlew test --tests '*TelegramSettingsStoreTest' -x processUiResources \
  --project-cache-dir /project-cache --no-daemon --console=plain
```

上述定向命令跳过 UI 资源任务，不能据此声称完整构建通过。前端任务定义在 `ui/build.gradle`；注意 `pnpm lint` 和 `pnpm prettier` 会修改文件，不把它们当作只读检查。

## 必须保持的行为

- 接收任务持久化成功后才能推进 Telegram 游标；去重、消息编辑和相册更新保持原瞬间身份；任务和重试按 Bot ID 隔离。
- 新附件与瞬间提交完成后才清理被替换的旧附件；提交结果未知时先核实引用，避免删除仍在使用的文件。
- 普通配置保存 Secret 引用。迁移旧 Token 时先写入并复读验证 Secret，再移除明文字段；失败不能丢失唯一凭据。已有引用失效时不回退明文，状态查询不触发迁移。
- Token 不进入日志、异常响应或普通模型序列化。真实验证需要用户输入凭据时使用可见浏览器，不要求在聊天中发送 Token，不读取或打印其他实例的凭据。
- 出站请求只接受公网 HTTPS 443；检查实际连接使用的全部 DNS 结果，不能校验后再次解析。保留 TLS 主机名校验、禁止重定向、响应大小上限、超时和停止取消能力。测试连接器不能变成产品配置中的绕过开关。
- 有界阻塞操作放在工作线程或 `boundedElastic`，不阻塞 WebFlux 事件循环。停用时释放工作线程和请求；不注销瞬间插件的模型，不误删用户内容。
- 管理 API 保持登录与角色权限保护；前端权限显示不能替代后端鉴权。页面展示真实状态，不用演示数据掩盖请求失败。

## 验证与完成标准

| 改动 | 应提供的证据 |
| --- | --- |
| 文档、注释 | 核对源码事实、命令、相对链接和 `git diff --check`；不为纯文档修改启动 Halo 或重复完整构建 |
| 业务逻辑、缺陷修复 | 能复现问题或验证行为的相关测试；不要只测试实现细节 |
| 前端行为或样式 | 相关组件测试、类型检查；视觉变化在实际 Halo 页面检查相应桌面或窄屏状态 |
| Secret、传输、存储、生命周期或发布 | 相关失败路径与回归测试，完整构建；涉及宿主兼容性时使用隔离 Halo 验收 |

运行与改动匹配的检查；通过后仅因新修改、失败或未解决疑点扩大或重复验证。测试失败时先定位原因，不通过删除断言、关闭检查或放宽安全限制使其通过。

集成脚本会覆盖目标 Halo 的测试配置并创建、编辑、删除测试内容，只允许用于专用实例和虚拟凭据。按 README 的隔离测试步骤操作；不复用真实 Bot 数据卷，不误停用户数据库或其他服务。测试结束关闭本任务启动的实例，除非用户要求保留。

交付说明写清实际改动、执行过的验证及其结果、尚未验证的边界。区分模拟 Telegram 与真实 Telegram、本地构建与远程 CI、仓库准备与应用市场审核。不要把“预计通过”写成“已通过”。功能或操作方式改变时同步更新对应文档。

## Code Review Rules

优先检查凭据泄露、数据丢失、重复发布、跨 Bot 混用、鉴权缺失、DNS/重定向绕过和生命周期泄漏。反馈应指出具体文件、触发条件与用户影响；已有项目格式交给工具处理，不用风格偏好阻断交付。涉及已有方案的修改时，核实当前实现与证据，不能仅依据过期计划判错。

## 指令维护依据

2026-10-27 核对 OpenAI 官方 [AGENTS.md 指导](https://learn.chatgpt.com/docs/agent-configuration/agents-md)、[Codex 最佳实践](https://learn.chatgpt.com/docs/learn/best-practices)和 [GPT-6 Astra 提示指导](https://developers.openai.com/api/docs/guides/latest-model#prompting-best-practices)。这里采用简短入口、明确决策边界、持续执行和适度验证，并结合本仓库约束编写；不是 OpenAI 规定的通用 Markdown 标准。模型选择、推理强度和工具权限由运行环境配置，本文件不声称改变这些设置。
