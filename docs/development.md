# 开发与测试

本文面向插件开发者。安装和配置见 [README](../README.md)，发布与应用市场流程见 [发布清单](releasing.md)。

## 构建

在仓库根目录的 Windows PowerShell 中执行：

```powershell
wsl --exec bash scripts/build.sh '-Pversion=1.1.0'
```

编译、测试和启动使用 WSL 中的 Docker，不在 Windows 宿主机直接运行 Gradle、Node 开发服务器或 Halo。

构建脚本在同一容器中使用 Java 21、Node 24、pnpm 10.12.4 和 Gradle Wrapper 8.14.6，执行完整 `clean build`，包含后端测试、前端测试、类型检查和打包。依赖缓存保存在 Docker 卷内，最终 JAR 位于 `build/libs/`。

省略 `-Pversion` 时使用 `gradle.properties` 中的开发版本。正式安装包通过参数注入版本，构建本身不会创建 Git tag 或发布 Release。注意 PowerShell 中需给 `-Pversion=…` 加引号。

## 隔离集成测试

`scripts/mock_telegram.py` 模拟 Telegram HTTPS 协议，不连接真实 Telegram。

**只允许在专用测试 Halo 上运行集成脚本**：脚本会覆盖测试配置、创建/编辑/删除瞬间并上传附件。不得使用生产 Bot、生产数据卷或其他项目实例。夹具在独立 Docker 桥接网络中模拟公网地址，只使用虚拟凭据；测试证书仅注入测试 Halo 信任库，插件没有安全绕过开关。

在 WSL 的仓库根目录执行：

```bash
bash scripts/prepare-test-tls.sh
docker compose -p telegram-moment-store-test -f scripts/compose.test.yaml up -d
```

TLS 脚本遇到已有密钥或信任库会拒绝覆盖；复用前检查测试证书有效期。默认使用 Halo 2.22.11，访问 `http://127.0.0.1:18092` 初始化，安装瞬间和本插件。先确认 18092、18094 端口及测试网络没有被其他测试实例占用。

将测试管理员凭据写入仓库外 JSON 文件，包含 `username`、`password`。在安装了 Python `cryptography` 的 WSL 中执行：

```bash
python3 scripts/integration_test.py --credentials /path/outside/repo/test-credentials.json
```

脚本覆盖 Secret 迁移、无 `from` 频道消息、原位编辑、重复投递、相册成员编辑、删除、标签过滤、附件下载失败与手动重试、Bot 重启恢复和匿名访问保护。

**当前已知测试问题**：原脚本仍断言整个模拟服务调用历史中没有 `sendMessage`，但新命令反馈独立于普通回执开关，因此会在该断言失败。不能把此前通过的单元测试或分段实测称为该脚本完整通过。具体证据和补充场景见 [PR #1 验证记录](plans/2026-10-27-pr1-windows-validation.md)。

测试结束停止本任务的实例，保留数据卷：

```bash
docker compose -p telegram-moment-store-test -f scripts/compose.test.yaml down
```

通过 `HALO_VERSION` 环境变量可切换 Halo 版本，必须使用新的 Compose 项目数据卷，不直接降级已有数据库。真实 Telegram 验证与模拟测试分别记录，不能互相替代。

## 实现与维护说明

- Telegram Update 持久化成功后才推进游标；发布自动重试最多 5 次，待处理与失败队列合计上限 1000。
- 附件上传失败保留原瞬间；新内容提交后才清理被替换的旧附件，删除交给 Halo 的资源清理流程。
- 任务和同步记录按 Bot ID 隔离。已完成记录保留消息与相册映射，以支持编辑和删除，不应手工清理这些记录。
- 旧瞬间接管识别 `chatId`、`messageId` 和 `attachmentNames` 注解。未保存的旧相册关系不能从 Telegram 历史自动重建，关联不完整时拒绝破坏性覆盖。
- 管理 API 要求登录及 `plugin:telegram-moment:manage` 权限；插件配置由 Halo 插件管理权限控制，前端显示不能替代后端鉴权。
- 管理页暂显示前 100 条待处理/失败任务，更多任务可通过管理 API 按任务名重试，或先处理当前显示的任务。

项目协作与安全约束见 [AGENTS.md](../AGENTS.md)。历史设计和验证证据保存在 [docs/plans](plans)，仅代表对应版本与当时测试，不是当前版本自动通过的依据。
