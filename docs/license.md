# 许可证与素材说明

## 项目许可证

Telegram 瞬间同步保持使用 **GNU General Public License version 3（GPL-3.0）**，本次上架资料整改不变更许可证。

- 完整许可条款：[仓库 LICENSE](../LICENSE)。该文件已有 GPL 第 3 版全文，不另建内容不同的许可证副本。
- 插件制品声明：[plugin.yaml](../src/main/resources/plugin.yaml) 中的 `spec.license`。
- 源码：[GitHub 仓库](https://github.com/Mystery00/halo-plugin-telegram-moment)。
- 1.1.0 对应源码：[v1.1.0](https://github.com/Mystery00/halo-plugin-telegram-moment/tree/v1.1.0)。

使用、修改和分发应遵守完整许可条款，包括适用的版权及许可声明保留、源码提供等义务。本说明不替代 LICENSE，也不将第三方组件的原始许可统一改为本项目许可。

## 应用市场许可证字段

在市场开发者控制台的应用资料中明确填写：

| 字段 | 填写内容 |
| --- | --- |
| 许可证 | `GPL-3.0` |
| 许可证链接（如提供此字段） | `https://github.com/Mystery00/halo-plugin-telegram-moment/blob/main/LICENSE` |

如果控制台使用下拉选项，选择与 GPL 第 3 版对应的选项，并检查保存后的详情。不能仅在 README 写明许可证而让商店许可证字段保持为空。

仓库 LICENSE、制品 `plugin.yaml`、README 和商店字段应一致。此次审核指出的是**商店许可证字段为空**，不是制品缺少许可声明；修改仓库文件不会自动更新商店表单。

## 图标与品牌

- 插件 PNG Logo：[logo.png](../src/main/resources/logo.png)，制作过程和来源见 [Logo 设计记录](plans/2026-10-27-plugin-logo.md)。该素材使用 OpenAI 图像生成工具制作，随本仓库按 GPL-3.0 分发；开发者仍需确认素材来源和可分发权利。
- 工具菜单图标使用 `~icons/ri/telegram-line`，来源为 Remix Design 的 Remix Icon 图标集合，不是本项目原创。已核对锁文件所用 `@iconify-json/ri@1.2.10` 的 `package.json` 与 `info.json`：图标集版本为 4.8.0，声明为 **Apache-2.0**，来源及许可链接为 [Remix Icon](https://github.com/cyberalien/RemixIcon) / [License](https://github.com/cyberalien/RemixIcon/blob/master/License)。项目 GPL-3.0 声明不替代该第三方许可。本记录只核对这里使用的图标，不代表已完成所有依赖的法律审计。
- 应用名称中的 Telegram、Halo 用于说明兼容服务。本插件不是 Telegram 或 Halo 官方产品，不表示获得官方认证、合作或推荐。
- 运行截图展示实际 Halo 后台；截图本身不改变 Halo 界面及其中第三方素材的权利归属。公开前应检查凭据、频道、用户等敏感信息。

## 审核依据

- [2.2 准确的上架信息](https://docs.halo.run/developer-guide/app-store/app-review-guidelines#22-准确的上架信息)
- [5.2 知识产权](https://docs.halo.run/developer-guide/app-store/app-review-guidelines#52-知识产权)

GPL-3.0 许可合规与应用市场审核是不同事项；补齐许可说明不代表审核已经通过。
