# 应用市场发布准备验证记录

## 范围

基线提交 `7f1ee9b`，分支 `rewrite/telegram-moment`。本轮完成仓库准备与本地正式版本验证，没有提交、推送、创建 tag、GitHub Release 或应用市场应用。

## 构建与静态验证

- WSL Docker 执行 `bash scripts/build.sh '-Pversion=1.0.0'`，完整 Gradle Wrapper `clean build` 成功。
- 后端 **67 项测试，0 失败、0 错误**；前端 **11 项测试通过**，类型检查、UI 打包与 JAR 打包通过。
- `actionlint` 验证 CI/CD 通过；Compose 配置校验、`git diff --check` 通过。
- Gradle 8.14.6 发行 SHA-256 已写入 Wrapper 配置。Java 21、Node 24、pnpm 10.12.4 与 Actions 配置一致；DevTools 0.6.2。
- 检查 JAR 内版本为 `1.0.0`，包含 `logo.svg`、`console/main.js`、`console/style.css`、Secret 设置表单及正确的 GitHub 元数据，不包含旧 Halo `logo.png`。

制品：`build/libs/plugin-telegram-moment-1.0.0.jar`

```text
SHA-256 ef498a84bdd65e8c92b2c9d115a3e12805d77c2287a17bc8ccea999b8db053e3
```

## 实际 Halo 验收

| 组合 | 同步与附件 | Secret / 配置 | 启停 / 卸载 |
| --- | --- | --- | --- |
| Halo 2.22.11 + 瞬间 1.15.0 | 通过 | 通过 | 通过 |
| Halo 2.27.1 + 瞬间 1.19.0 | 通过 | 通过 | 通过 |

两组分别使用 `telegram-moment-store-min`、`telegram-moment-store-current` Compose 项目和全新数据卷；未操作先前真实 Bot 的实例、数据或凭据。

模拟服务在专用 Docker 桥接网络中使用 `93.184.216.3`，通过 `https://telegram-moment-mock:443` 访问；证书保留主机名校验，只在测试 Halo 信任库导入自签证书。使用虚拟 Token，插件执行正常的公网地址检查，没有运行时测试放行开关。网络本身不是出站防火墙，不能用于真实凭据或真实生产配置。

验证项目：

- 旧 ConfigMap Token 迁移至 Secret 引用；无效引用不会回退旧 Token；状态查询不更新 ConfigMap；HTTP 配置被拒绝。
- 无 `from` 的频道文字、公开瞬间可访问、原位编辑与 HTML 转义、重复更新、`/redo` 保持瞬间标识。
- 双图相册与说明、实际附件 PNG 字节、缺少相册 ID 的成员编辑、回复非首成员 `/rm` 删除整组。
- 标签屏蔽、媒体下载失败后有限重试、失败任务保留、人工重试恢复、关闭自动回复后不发送回复。
- 接收游标先持久化、停止与重启后待处理相册继续发布、匿名访问管理接口被拒绝。
- Halo 实际提供 UI JS、CSS 和 SVG 图标；Setting 使用 Secret 组件。
- 插件停用进入 `DISABLED`，重新启用进入 `STARTED`，Bot 使用 Secret 恢复运行。
- 卸载成功，已发布瞬间与 Bot Secret 保留。

验收结束后执行各项目的 `docker compose down`，停止并移除临时容器和网络，测试数据卷保留。原有数据库、Redis 与历史停用实例未改动。

## 回归与审查

Secret、地址策略和解析器测试先观察缺失实现失败后实现。真实最低版本验收发现 ConfigMap 的 `creationTimestamp` 无法被未注册时间模块的 ObjectMapper 复制；补上真实元数据形态后测试失败，注册模块后全套测试和实际迁移通过。

新增测试还覆盖 Secret 创建失败、验证失败、配置更新冲突、恢复迁移、Token 不序列化、凭据轮换指纹、混合公网/私网 DNS 结果、IPv4/IPv6 特殊地址、重定向、响应超限和关闭取消。

独立只读审查结论：无 Critical、Important 或可确认的 Minor 缺陷。审查覆盖当前工作区及新增文件；没有以审查结论替代运行证据。

## 尚需平台侧完成

- 本轮没有重新使用真实 Telegram Token 验证公网可达性；HTTPS 模拟验证不能替代用户部署网络的实际连通验证。
- GitHub 托管 Actions 尚未运行本轮改动，需提交并推送后检查目标提交的 CI。
- 申请开发者、创建应用、填写应用资料并手动提交首版审核；通过后再配置真实 app ID 与 HALO_PAT。
- 当前 `skip-appstore-release: true`，不会自动上传市场。独立图标源文件为 SVG，若平台素材入口要求 PNG，可从源文件导出。

完整操作见 [发布清单](../releasing.md)。
