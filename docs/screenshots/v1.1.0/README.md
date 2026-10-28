# 1.1.0 应用市场运行截图

## 上传文件

| 文件 | 内容 |
| --- | --- |
| [runtime-status.png](runtime-status.png) | 工具菜单中的运行状态、任务队列和实时轮询日志，侧栏显示纸飞机图标 |
| [plugin-configuration.png](plugin-configuration.png) | 插件配置的「瞬间配置」页，显示 PNG Logo、发布者选择器、可见性、审核、标签、前后缀和来源链接选项 |

两张图片均为 **1440 × 1200 PNG**。请上传到应用市场的**截图字段**，不能仅用 Logo、封面或 README 中的图片替代该字段。

最终文件 SHA-256：

```text
runtime-status.png       69af922441e79ff1c1d8633cfea7f0ad97c4be5955c481bb2a6e6cdb8408952e
plugin-configuration.png 473c4907da7f06713ba75b86ed09c028cb4e35324dfce65ee65182f4720b995c
```

已逐像素比较原图与公开图片，确认遮盖区域以外完全一致。

## 来源与版本

- 截图时间：2026-10-28 16:36–16:37（UTC+8）。
- 截图环境：Windows 上的 Chromium，通过 agent-browser 操作；应用运行在 WSL Docker 中。是实际 Web 页面，不是设计稿或模拟器画面。
- Halo：2.22.14，取自运行容器的 `Implementation-Version`；瞬间插件：1.15.0。
- 本插件：GitHub Release **v1.1.0** 原始安装包，源码提交 `82d66edcc11af11cdd7db0b1e6854496a5f60946`。
- 制品：`plugin-telegram-moment-1.1.0.jar`，SHA-256：`9d24360a31b0e5595303d56c3c636dc315f979c32689c4ae700020f41eac2f22`，与 GitHub Release 资产摘要一致。
- 已核对制品版本、GPL-3.0 声明和 `logo.png`；制品中的 Logo 与仓库资源字节一致。运行页侧栏确认显示新版纸飞机图标。
- 状态页路径：`/console/telegram-moment`。
- 配置页路径：`/console/plugins/telegram-moment?tab=moment`。

## 真实性与隐私处理

复用了本项目旧开发实例和用户配置的测试 Bot，未重新填写、读取或输出 Bot Token，未运行覆盖配置的集成脚本。升级前对停止状态的数据卷创建了本地备份。

初次升级后出现本机 Fake-IP DNS 地址被安全策略拒绝的问题。由用户在配置页调整 API 代理后，状态为 `RUNNING`，实际长轮询连续成功；截图中的待处理、失败计数和日志均来自运行接口，不是演示数据。

截取前通过真实关闭按钮关闭了 Halo 的升级通知。截图后只做两处不透明遮盖：状态页的 Bot 用户名、配置页的发布账号。未改动运行状态、计数、时间、日志或配置值。原始截图保留在本地被 Git 忽略的 `workplace/appstore-v1.1.0/raw/`，不作为公开材料提交。

选择「瞬间配置」页是为了展示功能配置，同时避免公开 Secret 引用、自建代理域名及频道信息。没有点击保存或修改用户的发布配置。

## 验证边界

- 本轮确认安装包升级、现有配置复用、真实 Telegram 连接与轮询、两页加载及图标显示。
- 本轮未新发、编辑、删除 Telegram 消息，未重跑全部业务测试，不能据此声称全部主要路径已完成人工验收。
- 用户此前反馈 1.1.0 功能正常；具体人工测试与代码审查项目仍应由开发者在重新提交前确认，见 [重新提交清单](../../appstore-resubmission.md)。
- 截图由当前会话核对；未完成 agy 独立审查，用户明确要求不使用 agy。
- 截图准备完成不代表应用市场截图字段已经保存，也不代表审核通过。
