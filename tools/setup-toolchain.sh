#!/usr/bin/env bash
# Configure an existing JDK 17 and Android command-line tools, on any supported host.
set -euo pipefail
: "${JAVA_HOME:?Install JDK 17 and set JAVA_HOME}"
: "${ANDROID_HOME:?Install Android command-line tools and set ANDROID_HOME}"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
[ -f "$SDKMANAGER" ] || SDKMANAGER="$SDKMANAGER.bat"
[ -f "$SDKMANAGER" ] || { echo "Missing cmdline-tools/latest/bin/sdkmanager"; exit 1; }
"$SDKMANAGER" --licenses
"$SDKMANAGER" "platform-tools" "platforms;android-36" "build-tools;35.0.0"
echo "SDK ready. Gradle is provided by the checked-in wrapper."
