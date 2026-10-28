# 应用市场上架资料（1.1.0）

GitHub v1.1.0 已发布，应用市场首次审核因许可证字段为空和缺少截图而未通过。应用已由用户创建，真实 app-id 尚未记录到仓库。本文是维护者填写资料的清单，不作为市场 README 全文；本轮整改与人工确认项见 [重新提交清单](appstore-resubmission.md)。

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
| 许可证 | GPL-3.0（须在商店资料中单独填写，不能留空） |
| 许可证链接 | https://github.com/Mystery00/halo-plugin-telegram-moment/blob/main/LICENSE |
| 最低 Halo 版本 | 2.22.11 |
| 前置插件 | 瞬间（PluginMoments）1.15.0 或以上 |
| 项目主页 | https://github.com/Mystery00/halo-plugin-telegram-moment |
| 使用文档 | https://github.com/Mystery00/halo-plugin-telegram-moment#readme |
| 问题反馈 | https://github.com/Mystery00/halo-plugin-telegram-moment/issues |
| 已发布安装包 | [GitHub v1.1.0](https://github.com/Mystery00/halo-plugin-telegram-moment/releases/tag/v1.1.0) 中的 `plugin-telegram-moment-1.1.0.jar` |

Logo 已更新为 [PNG 图标](../src/main/resources/logo.png)，插件配置使用同一资源，可作为市场图标上传素材。图标由 OpenAI 图像生成工具制作，设计与素材来源见 [Logo 设计记录](plans/2026-10-27-plugin-logo.md)。封面图仍待安排；实际必填项、图片规格和字段长度以市场开发者控制台为准。

## 1.1.0 版本说明

- 补齐频道和授权私聊中的 `/rm`、`/redo`，支持命令提示及 Bot 用户名后缀。
- 增加独立命令反馈、成功命令清理和可选的原消息联动删除。
- 增加可见性、自动审核、默认标签、正文前后缀和频道来源链接配置。
- 编辑或重做时保留人工设置的发布者、可见性及审核状态。
- 发布用户、附件策略和分组改为资源选择器。
- 对未知删除目标和关联不完整的旧相册增加保护，避免误报成功或覆盖原内容。

## 已发布制品与运行截图

本轮从 GitHub Release 下载 1.1.0 原始 JAR，并核对资产摘要：

```text
9d24360a31b0e5595303d56c3c636dc315f979c32689c4ae700020f41eac2f22
```

已核对版本 `1.1.0`、名称「Telegram 瞬间同步」、内部 ID `telegram-moment`、GPL-3.0 声明及最新 PNG Logo。原测试实例从旧开发包升级到此制品后，用户配置代理，真实 Telegram 长轮询正常；本轮没有重跑完整构建，也没有用历史测试替代当前人工验收。

市场截图字段请上传：

- [运行状态页](screenshots/v1.1.0/runtime-status.png)
- [插件配置页](screenshots/v1.1.0/plugin-configuration.png)

两张均为真实页面截图，详细版本、时间和隐私处理见 [截图说明](screenshots/v1.1.0/README.md)。许可字段填写说明及素材来源见 [许可证说明](license.md)。

## 市场重新提交前待办

- 记录用户创建的真实 `app-…` ID，不使用内部插件 ID 或虚构占位值代替。
- 在商店填写 GPL-3.0 并上传本轮两张运行截图，检查保存后的字段与预览。Logo 与封面图本轮不改动。
- 处理集成测试旧断言问题并完成剩余验收，见 [本地验证记录](plans/2026-10-27-pr1-windows-validation.md)。用户确认配置页面没有问题，不等于市场审核或所有运行边界已通过。
- 本次保留 `.github/workflows/cd.yaml` 的 `skip-appstore-release: true`，不创建新 tag、Release，也不自动重新提交市场审核。
- 如后续授权自动发布，再配置真实 app-id；由用户在 GitHub Actions Secret 中保存最小权限 `HALO_PAT`，不要在聊天或仓库中粘贴 PAT。
- 完整发布流程见 [发布清单](releasing.md)，版本审核和正式发布另行执行。
