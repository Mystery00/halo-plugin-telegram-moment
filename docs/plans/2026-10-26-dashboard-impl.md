# 管理页重构实施计划

> 执行方式：executing-plans，当前会话顺序实施，结束后独立审查。

**目标：** 实现用户选定的第一版日志优先管理页。
**架构：** HomeView 保留现有 API 与操作编排；LogPanel 独立承担日志筛选与展示；dashboard.ts 提供纯筛选函数。
**技术栈：** Vue 3、TypeScript、Halo 组件、Remix Icon、Vitest。
**设计：** [设计文档](2026-10-26-dashboard-design.md)。

## 全局约束

中文 UI、注释与文档，英文日志；WSL Docker 编译启动；不改真实 Token、不发送 Telegram 消息。用户已经选择视觉方案，继续实施，不增加设计确认环节。

## 审查重点

筛选组合与空结果、暂停期间在途响应、清空与刷新竞争、首次加载/接口失败、窄屏长日志与任务错误。

## 步骤

- [x] 在 dashboard.test.ts 增加筛选组合、大小写、空白、无匹配和原数组顺序不变测试，运行观察失败。
- [x] 在 dashboard.ts 实现 filterLogs(entries, {query, level, source})，验证测试通过。
- [x] 新建 components/LogPanel.vue，完成工具栏、筛选状态、日志列表和空状态。
- [x] 重构 HomeView.vue：状态条、更多操作、任务、暂停刷新、错误及加载状态。
- [x] WSL Docker 完整构建；升级现有本地 Halo 插件；使用 agent-browser 验证桌面、窄屏、搜索、级别/来源筛选、暂停/恢复及菜单。
- [x] 独立代码审查与截图对比，修正后记录 design-qa.md；本轮不自动提交。

## 执行记录

- 基线：ed8cb09，rewrite/telegram-moment，工作区干净。
- 筛选测试先失败后通过；组件测试发现快速暂停恢复的在途响应问题，修复后通过；本地显示时间检索也完成失败到通过回归。
- 最终 11 项前端测试、52 项后端测试、类型检查与完整构建通过。
- 独立审查复核无新增 P1/P2；视觉验收见 [design-qa.md](../../design-qa.md)。
- 用户已确认第一版，直接在现有分支实施；无新增依赖或后端配置变更。
