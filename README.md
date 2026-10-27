# Telegram 瞬间同步

<img src="src/main/resources/logo.png" alt="Telegram 瞬间同步插件图标" width="112" height="112" />

将 Telegram 频道或授权私聊消息同步为 Halo 瞬间，支持图文相册、消息编辑同步、删除与重做命令，以及可见性、标签等发布设置。

## 功能

- **内容同步**：支持文字、图片、视频、GIF、静态/视频贴纸，相册合并为一条瞬间。
- **编辑同步**：修改原消息时更新已有瞬间，保留评论关联及人工设置的发布者、可见性和审核状态。
- **命令操作**：回复原消息即可重新同步或删除对应瞬间，可选择是否联动删除 Telegram 原消息。
- **发布设置**：配置公开或私有、自动审核、默认标签、正文前后缀及频道来源链接。
- **管理与排错**：选择发布用户和附件存储位置，查看运行日志、失败任务并手动重试。

## 使用条件

- Halo **2.22.11 或以上**，已安装并启用「瞬间」插件 **1.15.0 或以上**。
- 一个 Telegram Bot，以及能访问 Telegram Bot API 的 Halo 服务器。
- 支持频道和指定用户的私聊，**不支持普通群、超级群**。
- 单个媒体最大 **20 MiB**；不支持音频、文档和 TGS 动画贴纸。

## 快速开始

1. **安装插件**：在 Halo「插件」页面上传安装包并启用。
2. **配置 Bot**：打开插件配置，在「Bot Token 凭据」中创建或选择 Secret，将 Bot Token 填入 `token` 键。不要把 Token 写进 API Endpoint。
3. **选择消息来源**：
   - **频道**：将 Bot 加入频道并授予所需权限，启用频道监听。建议填写频道 Chat ID；留空会接收该 Bot 能收到的所有频道消息。
   - **私聊**：启用私聊监听，填写允许的发送者**数字 ID**，不能填用户名。
4. **选择发布位置**：选择瞬间发布者、附件存储策略；附件分组可选。
5. **启动同步**：进入「工具 → Telegram 瞬间同步」，在「更多」中选择「应用配置并重启」，确认状态为「运行中」，再发送消息测试。

> **修改配置或 Secret 后，都需要重新应用配置并重启 Bot。** 新瞬间默认公开且自动通过审核；同步私人内容前，请先检查来源范围和可见性设置。

## 常用配置

| 配置 | 默认行为与说明 |
| --- | --- |
| 可见性与审核 | 新瞬间默认公开、自动通过审核；可改为私有或需要审核。仅影响新建瞬间。 |
| 默认标签 | 默认不附加；支持中英文逗号或换行分隔，与正文 hashtag 去重合并。 |
| 正文前后缀 | 默认不添加；按纯文本显示，不解析 HTML 或 Markdown。 |
| 来源链接 | 默认关闭；仅为频道消息添加 Telegram 原消息链接，私聊不添加。私有频道链接仍需访问权限。 |
| 屏蔽标签 | 跳过包含指定完整 hashtag 的频道消息。 |
| 普通消息回执 | 默认关闭，可在频道或私聊配置中开启；命令反馈不受此开关影响。 |

编辑消息或执行 `/redo` 时，会应用当前标签、前后缀和来源链接配置，但不会覆盖 Halo 中已有的发布者、可见性及审核状态。

## 命令

在频道或授权私聊中使用：

| 操作 | 命令 |
| --- | --- |
| 回复原消息，重新同步 | `/redo` |
| 回复原消息，删除对应瞬间及关联附件 | `/rm` |
| 查看命令说明 | `/help` 或 `/start` |

支持 `/redo@Bot用户名`、`/rm@Bot用户名`。对已记录相册成员执行 `/redo` 会保留其他成员；执行 `/rm` 会删除**整组瞬间**。

成功执行后默认清理命令消息，**默认不删除 Telegram 原消息**。如需联动删除，请在「命令配置」中单独开启，并授予 Bot 删除权限。Telegram 权限或消息年龄限制可能导致清理失败，但不会回滚已经完成的 Halo 操作。

## 常见问题

**修改配置后没有生效？** 选择「应用配置并重启」。若仍未同步，检查 Bot 状态、来源 ID、发布者、存储策略和瞬间插件是否已启用；需要查看日志时先开启日志记录并重启 Bot。

**无法连接 Telegram？** 确认是 Halo 服务器能够访问 Telegram，而不只是浏览器所在电脑。API Endpoint 留空使用官方地址；自定义代理仅支持 HTTPS 443，需同时支持 API 和文件下载。私网代理设置见[高级配置与排错](https://github.com/Mystery00/halo-plugin-telegram-moment/blob/main/docs/usage.md)。

**提示 401、403 或 409？** 分别检查 Token、Bot 权限、是否有其他程序使用同一 Bot 长轮询或设置了 webhook。建议为本插件使用专用 Bot。

**任务失败或漏掉历史消息？** 修复原因后可在管理页手动重试。Telegram 不会无限期保存未接收的消息，长时间停用后不保证补齐历史内容。

## 隐私与升级提醒

- 插件向 Telegram 或你配置的代理请求消息和媒体，将内容保存到 Halo 及所选附件存储；不包含遥测或第三方统计。自定义代理可以接触 Token 和消息，请仅使用可信服务。
- Token 使用 Halo Secret 保存；同步记录保存在 Halo 中。页面日志会脱敏，不记录完整消息。详细存储与保留说明见[使用说明](https://github.com/Mystery00/halo-plugin-telegram-moment/blob/main/docs/usage.md#数据保留与卸载)。
- 升级前备份数据库和附件。旧版 Token 迁入 Secret 后，不应直接降级；旧相册关联不完整时会拒绝覆盖并保护原内容。
- 停用不会删除已同步内容；卸载也不会由插件主动清除瞬间、附件或 Secret，需按需手动清理。

## 帮助与反馈

[详细使用说明](https://github.com/Mystery00/halo-plugin-telegram-moment/blob/main/docs/usage.md) · [更新日志](https://github.com/Mystery00/halo-plugin-telegram-moment/blob/main/CHANGELOG.md) · [问题反馈](https://github.com/Mystery00/halo-plugin-telegram-moment/issues) · [开发文档](https://github.com/Mystery00/halo-plugin-telegram-moment/blob/main/docs/development.md)

[GPL-3.0](https://github.com/Mystery00/halo-plugin-telegram-moment/blob/main/LICENSE) © Mystery0
