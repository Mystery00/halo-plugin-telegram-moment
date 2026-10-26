# 发布与应用市场提交

本文件是仓库发布清单，不能替代平台审核。当前尚未创建市场应用，CI/CD 保持 `skip-appstore-release: true`。

本轮已完成的本地构建、兼容性与安全验证见 [验证记录](plans/2026-10-26-store-release-validation.md)。下列清单用于每次正式发布时复核。

## 构建与版本

使用 Java 21、Node 24、pnpm 10.12.4 和仓库 Gradle Wrapper 8.14.6。WSL Docker 与 Actions 均执行完整 `clean build`，包括 Java 测试、UI 测试、类型检查和打包。DevTools 使用 0.6.2；运行 API 基线保留 Halo 2.22.11，避免引入模板的 SNAPSHOT。

```powershell
wsl --exec bash /mnt/d/IdeaProjects/halo-plugin-telegram-moment/scripts/build.sh '-Pversion=1.0.0'
Get-FileHash build/libs/plugin-telegram-moment-1.0.0.jar -Algorithm SHA256
```

版本参数必须加引号，避免 Windows PowerShell 拆分参数。日常构建默认 SNAPSHOT；发布通过 `-Pversion` 注入版本，不修改源文件。发布 tag 支持 `v1.0.0`、`1.0.0`、`v1.0.0-rc.1` 等，预发布仅接受 alpha/beta/rc 数字序号。

## 发布前检查

- [ ] 工作区提交并推送，目标提交的 GitHub CI 通过。
- [ ] 使用正式版本参数构建，检查 JAR 内的 `plugin.yaml` 版本、`logo.svg`、`console/main.js`、`console/style.css`。
- [ ] 在最低支持版本和当前稳定 Halo 中安装；安装瞬间依赖，检查启停、配置、管理权限和卸载。
- [ ] 检查 Secret 迁移、无效引用和公网 HTTPS 代理；参考本轮验证记录，区分模拟协议与真实 Telegram 验证。
- [ ] 整理 [版本说明](../CHANGELOG.md)，确认 README、隐私说明、兼容性和截图与实际版本一致。
- [ ] 手动创建版本 tag 和 GitHub Release。CD 会再次完整验证后上传构建产物；失败时不得手动绕过检查。

## 首次上架

1. 申请 Halo 应用市场开发者，创建插件应用，记录真实的 `app-…` ID。
2. 填写应用简介、GPL-3.0 许可、兼容性、仓库与问题反馈链接、使用说明和数据流向。
3. 上传正式 JAR 和插件图标。图标是本仓库原创 SVG，同仓库 GPL-3.0；未沿用 Halo 模板品牌图标。
4. 使用真实页面截图，例如 [桌面管理页](plans/dashboard/desktop.png) 和 [移动端日志](plans/dashboard/mobile-logs.png)。提交前复查截图是否包含站点或频道信息；`reference.png` 是设计概念稿，不可作为功能截图。
5. 创建首个版本草稿，人工提交审核，处理平台反馈。仓库内检查通过不代表市场审核通过。

## 首次审核通过后启用自动同步

在 GitHub Actions 仓库 Secret 中保存 `HALO_PAT`，只授予市场发布所需权限。在 `.github/workflows/cd.yaml` 的 `cd` job 设置真实 `app-id`，将 `skip-appstore-release` 改为 `false`，并添加：

```yaml
    secrets:
      halo-pat: ${{ secrets.HALO_PAT }}
```

不要把 PAT、Bot Token 或占位应用 ID 写入仓库。预发布渠道、审核规则与 Token 权限以平台当前说明为准，启用前重新核对。

依据：[官方发布流程](https://docs.halo.run/developer-guide/app-store/publish-app)、[审核指南](https://docs.halo.run/developer-guide/app-store/app-review-guidelines)、[发布检查清单](https://docs.halo.run/developer-guide/plugin/release-checklist)、[工作流 v5.0.1](https://github.com/halo-sigs/reusable-workflows/tree/v5.0.1)。
