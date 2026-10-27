# 应用市场首次上架资料（1.1.0 草稿）

应用已由用户创建，真实 app-id 尚未记录到仓库；尚未提交版本审核或正式发布。本文是维护者填写资料的清单，不作为市场 README 全文。

## 插件描述（可直接填写）

将 Telegram 频道或授权私聊消息同步为 Halo 瞬间，支持图文相册、消息编辑同步、删除与重做命令，以及可见性、标签等发布设置。

## README

市场 README 从 [GitHub 仓库首页](https://github.com/Mystery00/halo-plugin-telegram-moment#readme) 同步，内容以面向使用者的 [README.md](../README.md) 为唯一维护来源，不另存重复的长篇市场介绍。

README 包含功能、使用条件、安装配置、命令、常见问题及必要隐私和升级提醒。代理细节和数据保留移至 [使用说明](usage.md)，构建和集成测试移至 [开发文档](development.md)。

市场同步读取 GitHub 上已推送的 README；每次更新文案并推送后，需在市场刷新同步。本文中的本地验证、SHA-256 和发布待办无需粘贴到市场介绍。

## 基本信息

| 项目 | 内容 |
| --- | --- |
| 应用名称 | Telegram 瞬间同步 |
| 类型 | Halo 插件 |
| 插件内部 ID | `telegram-moment`（不是市场 app-id） |
| 首次市场版本 | `1.1.0` |
| 作者 | Mystery0 |
| 许可证 | GPL-3.0 |
| 最低 Halo 版本 | 2.22.11 |
| 前置插件 | 瞬间（PluginMoments）1.15.0 或以上 |
| 项目主页 | https://github.com/Mystery00/halo-plugin-telegram-moment |
| 使用文档 | https://github.com/Mystery00/halo-plugin-telegram-moment#readme |
| 问题反馈 | https://github.com/Mystery00/halo-plugin-telegram-moment/issues |
| 待上传安装包 | `build/libs/plugin-telegram-moment-1.1.0.jar` |

Logo 与封面图本轮不处理，等待用户后续安排。实际必填项、图片规格和字段长度以市场开发者控制台为准。

## 1.1.0 版本说明

- 补齐频道和授权私聊中的 `/rm`、`/redo`，支持命令提示及 Bot 用户名后缀。
- 增加独立命令反馈、成功命令清理和可选的原消息联动删除。
- 增加可见性、自动审核、默认标签、正文前后缀和频道来源链接配置。
- 编辑或重做时保留人工设置的发布者、可见性及审核状态。
- 发布用户、附件策略和分组改为资源选择器。
- 对未知删除目标和关联不完整的旧相册增加保护，避免误报成功或覆盖原内容。

## 本地安装包验证

2026-10-27 使用 WSL Docker 执行 `bash scripts/build.sh '-Pversion=1.1.0'` 完整构建通过：后端 109 项测试（0 失败、0 错误、0 跳过），前端 11 项测试、类型检查与打包通过。

已检查 JAR 内版本为 `1.1.0`、显示名称为「Telegram 瞬间同步」、内部 ID 为 `telegram-moment`，并包含图标及前端 JS/CSS。该次构建产物的 SHA-256：

```text
8bc14115099b60058acfa5f7ff4a306fdcf2ea39931735c1c4e13a8870925f63
```

默认开发版本为 `1.1.0-SNAPSHOT`，待上传包通过 `-Pversion=1.1.0` 注入版本号。构建不代表已发布；此安装包尚未做安装升级实测。局域网预览实例仍运行 `1.1.0-rc.1`，未被本次文档修改覆盖。

## 正式发布前待办

- 记录用户创建的真实 `app-…` ID，不使用内部插件 ID 或虚构占位值代替。
- 完成用户安排的 Logo、封面图及当前版本真实页面截图；隐藏凭据、私人频道与用户信息，不将历史截图冒充当前页面。
- 处理集成测试旧断言问题并完成剩余验收，见 [本地验证记录](plans/2026-10-27-pr1-windows-validation.md)。用户确认配置页面没有问题，不等于市场审核或所有运行边界已通过。
- 本次保留 `.github/workflows/cd.yaml` 的 `skip-appstore-release: true`，不创建 tag、GitHub Release 或提交市场审核。
- 如后续授权自动发布，再配置真实 app-id；由用户在 GitHub Actions Secret 中保存最小权限 `HALO_PAT`，不要在聊天或仓库中粘贴 PAT。
- 完整发布流程见 [发布清单](releasing.md)，版本审核和正式发布另行执行。
