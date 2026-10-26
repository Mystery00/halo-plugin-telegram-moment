#!/usr/bin/env bash
set -euo pipefail
# 与 GitHub Actions 使用相同的 Wrapper 和完整检查链，参数透传给 Gradle。
project_root="$(cd "$(dirname "$0")/.." && pwd)"
docker build --progress=plain -f "$project_root/scripts/Dockerfile.build" -t telegram-moment-build:local "$project_root"
docker run --rm -e CI=true -v "$project_root:/workspace" \
  -v telegram-moment-build-ui-modules:/workspace/ui/node_modules \
  -v telegram-moment-gradle-cache:/home/gradle/.gradle \
  -v telegram-moment-gradle-project-cache:/project-cache -w /workspace telegram-moment-build:local \
  bash ./gradlew clean build --project-cache-dir /project-cache --no-daemon --console=plain "$@"
