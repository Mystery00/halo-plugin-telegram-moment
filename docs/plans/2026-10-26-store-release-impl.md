# 应用市场发布准备实施计划

> 使用 executing-plans 顺序实施；用户已确认审查清单，最终独立审查，不重复询问方案。

**目标：** 产出具备上架准备条件的源码、正式版本制品与可复核发布记录。
**架构：** Secret 读取/迁移独立为 TelegramSettingsStore；HTTPS/DNS 规则独立为 EndpointPolicy 与安全解析器；TelegramApi 保留调用接口并更换受控传输。
**技术栈：** Java 21、Halo 2.22.11 API、Reactor Netty/WebClient、Vue 3、Gradle Wrapper、Node 24/pnpm 10.12.4。
**设计：** [发布准备设计](2026-10-26-store-release-design.md)。

## 全局约束与审查重点

中文文档/注释、英文日志；WSL Docker 编译启动；不读取或打印真实 Token，不操作真实配置，不推送或发布。

重点覆盖：迁移部分失败不丢凭据；引用失效不回退明文；双栈 DNS 混合私网结果拒绝；校验与实际连接一致；停止取消、响应超限、重定向不泄露 Token；CD 先验证再上传。

## 任务

- [x] 更新 CI/CD、Wrapper、DevTools 与 Docker 构建路径；验证 YAML、Wrapper 和同一路径 clean build。
- [x] 添加 TelegramSettingsStoreTest，先验证 Secret 解析、迁移成功/失败、只读状态和引用优先测试失败；实现并通过全套测试。
- [x] 添加 EndpointPolicyTest 与传输边界测试，先观察失败；实现 HTTPS、公网 DNS、禁重定向、请求取消及大小上限，运行全套测试。
- [x] 更新设置表单、元数据、Logo、README、隐私/升级/卸载说明、1.0.0 版本说明和上架清单。
- [x] 构建 1.0.0 正式 JAR，检查内容/版本/SHA-256；在隔离最低/当前 Halo 实例进行验收并关闭实例。
- [x] 独立审查，修复实质问题，记录验证结果与仍需平台完成的事项。

## 执行记录

- 基线 7f1ee9b，rewrite/telegram-moment；工作区干净。
- 用户确认尚未创建市场应用，因此维持跳过市场发布；不请求 HALO_PAT。
- 完整构建：67 项后端、11 项前端测试通过，UI 类型检查与正式 JAR 打包通过；actionlint 通过。
- Halo 2.22.11 + 瞬间 1.15.0：HTTPS 模拟同步、Secret 迁移、无效引用、只读状态、HTTP 拒绝、静态资源、启停与卸载验证通过。
- 真实 ConfigMap 的 creationTimestamp 暴露迁移序列化遗漏；补充失败测试后注册 JavaTime 模块，完整构建与实际迁移通过。
- 独立审查：无 Critical/Important/Minor 实际缺陷；两个版本的运行验收均通过，详见 [验证记录](2026-10-26-store-release-validation.md)。
- 决策：独立图标采用原创 SVG；若市场素材入口仅收 PNG，需从 SVG 导出上传图。HTTPS 夹具使用专用桥接网络与虚拟凭据，不在产品内提供内网/HTTP 绕过。
