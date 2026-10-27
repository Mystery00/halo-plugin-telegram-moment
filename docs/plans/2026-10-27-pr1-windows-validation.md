# PR #1 Windows 本地验证记录

## 验证对象

- PR：#1，`feat: 补齐 Telegram 命令、发布选项与资源选择器`。
- 提交：`e9f4f6bcc8068555f287f5fdf543bad5b8324615`。
- 本地分支：`codex/telegram-commands-publishing-options`。
- 构建命令：`wsl --exec bash scripts/build.sh '-Pversion=1.1.0-rc.1'`。
- 产物：`build/libs/plugin-telegram-moment-1.1.0-rc.1.jar`。
- 隔离实例：Compose 项目 `telegram-moment-pr1-test`，Halo 2.22.11、瞬间 1.15.0，独立 H2 数据卷，只使用虚拟 Telegram 凭据。

## 已通过

- WSL Docker 完整 `clean build`：后端 109 项测试，0 失败、0 错误、0 跳过；前端 11 项测试通过；类型检查、前端打包、JAR 打包通过。
- `git diff origin/main...HEAD --check` 通过。
- 真实 Halo + 模拟 Telegram：插件启动、旧 Token 迁移至 Secret、无 from 频道发布、公开访问、原位编辑与 HTML 转义、重复投递身份、redo 身份、双图相册和附件实际字节、非首相册成员编辑、通过非首成员删除整组、标签过滤、附件失败保留和手工重试恢复。
- 单独续跑原脚本后半部分：接收游标持久化、待处理相册跨 Bot 重启恢复、匿名管理访问拒绝、失效 Secret 引用不回退、状态接口只读、生产配置拒绝 HTTP 端点。
- 补充实际 API 场景：PRIVATE 发布、approved=false、无审核时间、默认标签去重、前缀 HTML 转义、公开频道 username 来源链接。
- 人工将瞬间设为 PUBLIC/已审核后再编辑，管理字段保留，身份不变。
- 授权私聊正常发布且没有来源链接；关闭普通回执时不新增 sendMessage；`/redo@moment_test_bot` 原位更新、独立命令反馈；`/rm` 删除原瞬间，命令不发布成瞬间。

## 发现的问题

`scripts/integration_test.py:185` 仍断言模拟服务调用历史中完全没有 `sendMessage`。但前面已发送 `/redo` 与 `/rm`，本 PR 约定命令反馈独立于普通回执开关，因此原脚本在此失败，**原集成脚本不能宣称完整通过**。

补充场景通过前后调用数量验证普通私聊不发送回执、命令会发送反馈，确认此失败来自旧断言与新契约不一致。未删除断言，未修改插件源码。建议后续更新脚本：分别验证普通消息回执关闭与命令反馈开启，而非断言整个调用历史没有 sendMessage。

临时补充脚本位于被 Git 忽略的 `workplace/pr1-remaining.py`、`workplace/pr1-new-scenarios.py`。补充脚本首次人工更新 Moment 时遇到 Halo 乐观锁 409，按最新版本重新读取后重试，最终场景通过；这不是插件发布失败。

## 尚未验收的边界

- 三种资源选择器的完整旧值回显、选择、保存、分组清空及视觉验收。浏览器成功读取了安装后的插件列表，但自动化表单操作遇到会话/元素定位问题，不能以此判断选择器成功或失败。
- 真实 Telegram 网络、权限和删除年龄限制。
- Telegram 反馈/清理失败及持久化恢复的实际集成注入，本轮只覆盖已有单元测试和正常命令路径。
- 当前更高版本 Halo/瞬间组合、旧相册保护的实际宿主场景、全部未授权命令边界。

## 清理与交付

本轮不合并、不推送、不发布，不修改插件源码。结束时停止并移除本任务 Compose 容器与网络，保留专用测试数据卷；不操作已有数据库和其他项目容器。临时账号及浏览器认证状态置于仓库外，结束后清除对应临时文件。
