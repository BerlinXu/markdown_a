#!/usr/bin/env bash
# Use the pinned wrapper on Linux, macOS and Git Bash.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [ -z "${JAVA_HOME:-}" ] && [ -d "$HOME/.qoder/toolchain/jdk" ]; then export JAVA_HOME="$HOME/.qoder/toolchain/jdk"; fi
if [ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ] && [ ! -f local.properties ]; then
  for candidate in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" "$HOME/AppData/Local/Android/Sdk" "$HOME/.qoder/toolchain/android-sdk"; do
    if [ -d "$candidate/platforms" ]; then export ANDROID_HOME="$candidate"; break; fi
  done
fi
if [ "$#" -eq 0 ]; then set -- :app:testDebugUnitTest :app:assembleRelease; fi
exec bash ./gradlew --console=plain "$@"
