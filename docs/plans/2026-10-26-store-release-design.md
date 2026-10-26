# 应用市场发布准备设计

用户已确认落实上一轮审查列出的仓库改造；尚未创建应用市场应用，本轮只完成仓库发布准备，不创建 Release、不推送、不上架。

## 目标与边界

统一 GitHub Actions 与 WSL Docker 的真实构建路径，补全元数据、品牌、文档，修正凭据存储和出站目标验证。保留 Halo >=2.22.11、瞬间 >=1.15.0、Java 21 的运行基线，前端不整体迁移模板。Gradle 先采用 8.14 的维护版本，避免发布前同时跨 Gradle 9 与 Lombok 9；DevTools 更新到 0.6.2。官方模板的 SNAPSHOT 不引入生产依赖。

## 构建与发布

- CI/CD 使用 halo-sigs/reusable-workflows v5.0.1，Node 24、pnpm 10.12.4、Java 21。
- Gradle Node 插件使用环境中已准备好的 Node/pnpm，关闭隐式 Node 18 下载；Docker 在同一容器执行 Gradle Wrapper clean build，包括全部 UI/后端检查。
- 发布工作流先通过完整检查，再调用跳过 check 的官方 CD；校验 Release tag 为正式或预发布语义版本。
- 应用市场首次审核前始终跳过自动同步；文档提供 app-id、HALO_PAT 后续配置方法，不提交占位应用 ID。
- 正式制品按 1.0.0 验证，开发默认仍可使用 SNAPSHOT；不自动创建 tag。

## 凭据

Setting 使用已有 secret 组件，字段 botTokenSecretName，键 token。ConfigMap 仅保存引用。后端通过 Secret 解析 Token，仅保留内存值，不序列化到普通模型、日志或状态接口。

启动时迁移旧 bot.botToken：创建或校验插件托管的确定性 Secret，确认内容一致后更新 ConfigMap 并移除明文字段。失败时停止启动，不删除尚未迁移的唯一凭据；已有 Secret 引用优先，不以旧值覆盖。状态查询只读，不触发迁移。保留用户创建的 Secret，卸载策略写入说明。

## 出站请求

保留官方 API 默认值与公开 HTTPS 代理（支持路径前缀）；拒绝 HTTP、凭据/查询/片段、路径穿越和非 443 端口。连接时验证实际 DNS 解析的全部 IPv4/IPv6 地址，拒绝内网、环回、链路本地、保留地址、IPv4 映射/IPv6 转换等绕过形式；将已验证结果直接交给连接层，避免校验后再次解析的 DNS 重绑定窗口。禁用自动重定向和代理环境继承，保留超时、限流、响应大小限制与停止取消语义。通过 WebClient/Reactor Netty 实现异步网络访问；同步工作线程可有界等待，不在 WebFlux 事件循环阻塞。

测试通过构造器注入局部测试 HTTP 连接器验证协议，不添加运行时配置绕过。模拟集成需迁移为受控 HTTPS 夹具，不能为真实插件放开私网访问。原始 HTTP 集成脚本须明确更新或标记不适用于发布版。

## 资料与验收

修正 GitHub 元数据，替换默认 Halo 图标为独立插件标识，记录素材来源。README 补 Secret 配置、代理限制、数据流向、隐私、升级和卸载说明；提供真实界面截图、版本说明与发布清单。

采用专用临时容器和数据卷验证最低 Halo 与当前稳定 Halo 版本，绝不覆盖已有真实 Bot 配置。验证安装/启停/配置/权限/卸载和制品内容；不要求用户再次发送真实频道消息。记录无法在无真实 Token 条件下重做的路径。验证完成停止临时运行实例。

依据：[官方工作流](https://github.com/halo-sigs/reusable-workflows/tree/v5.0.1)、[发布流程](https://docs.halo.run/developer-guide/app-store/publish-app)、[敏感数据与出站请求](https://docs.halo.run/developer-guide/plugin/security/outbound-http)。
