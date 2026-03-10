# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目简介

这是一个 **Halo 插件**，用于将 Telegram 频道消息同步为 Halo 瞬间（Moment）。项目采用前后端分离架构：
- **后端**：Java 21 + Spring Boot（Halo 插件 SDK）
- **前端**：Vue 3 + TypeScript + Rsbuild

## 开发命令

### 后端

```bash
# 启动 Halo 开发服务器（含热重载）
./gradlew haloServer

# 构建插件 JAR（输出到 build/libs/）
./gradlew build

# 仅运行后端测试
./gradlew test

# 运行所有检查（含前端测试）
./gradlew check
```

### 前端（在 ui/ 目录下）

```bash
cd ui
pnpm install

pnpm dev          # 开发模式（watch）
pnpm build        # 生产构建
pnpm lint         # oxlint + eslint
pnpm prettier     # 格式化代码
pnpm test:unit    # Vitest 单元测试
pnpm type-check   # TypeScript 类型检查
```

## 项目架构

### 后端结构

- **包路径**：`vip.mystery0.halo.telegrammoment`
- **入口类**：`TelegramMomentPlugin.java` — 继承 `BasePlugin`，管理插件生命周期
- **配置文件**：`src/main/resources/plugin.yaml` — 插件元数据，要求 Halo >= 2.22.0

依赖通过 `run.halo.tools.platform:plugin:2.22.0` BOM 统一管理，测试使用 JUnit 5 + Mockito。

### 前端结构

- **入口**：`ui/src/index.ts` — 用 `definePlugin()` 注册路由和菜单
- **视图**：`ui/src/views/` — Vue 单文件组件
- **构建工具**：Rsbuild（基于 Rspack）+ pnpm
- **路径别名**：`@/*` → `./src/*`

**核心依赖版本**（须与 Halo 版本对应）：
- `@halo-dev/api-client@2.22.0`
- `@halo-dev/components@2.22.0`
- `@halo-dev/ui-shared@2.22.0`

### CI/CD

- `.github/workflows/ci.yaml`：push/PR 到 main 时触发，使用 halo-sigs 可复用工作流
- `.github/workflows/cd.yaml`：GitHub Release 发布时触发，生成制品

## 关键约定

- 代码风格：UTF-8、LF 换行、4 空格缩进、最大行长 120 字符（见 `.editorconfig`）
- 前端包管理器：**pnpm**（不使用 npm/yarn）
- Java 版本：**21**
