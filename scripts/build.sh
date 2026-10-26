#!/usr/bin/env bash
set -euo pipefail
# 在 WSL 或 Linux 中运行，构建依赖与缓存全部保存在容器卷中。
project_root="$(cd "$(dirname "$0")/.." && pwd)"
docker run --rm -v "$project_root:/workspace" \
  -v telegram-moment-build-ui-modules:/workspace/ui/node_modules \
  -v telegram-moment-pnpm-store:/pnpm/store -w /workspace/ui node:24-bookworm \
  sh -c 'corepack enable && pnpm install --frozen-lockfile --store-dir /pnpm/store && pnpm test:unit && pnpm build'
docker run --rm -v "$project_root:/workspace" \
  -v telegram-moment-gradle-cache:/home/gradle/.gradle \
  -v telegram-moment-gradle-project-cache:/project-cache -w /workspace gradle:8.14-jdk21 \
  gradle build -x :ui:pnpmBuild -x :ui:pnpmCheck --project-cache-dir /project-cache --no-daemon --console=plain
