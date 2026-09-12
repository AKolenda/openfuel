#!/usr/bin/env sh
# SPDX-License-Identifier: AGPL-3.0-only
set -eu
cd "$(dirname "$0")"
command -v java >/dev/null 2>&1 || { echo 'Install JDK 17.' >&2; exit 1; }
command -v gradle >/dev/null 2>&1 || { echo 'Install Gradle 8.11.1 or use the included GitHub Actions workflow. No APK has been built yet.' >&2; exit 1; }
[ -n "${ANDROID_HOME:-}" ] || { echo 'Set ANDROID_HOME to an Android SDK containing platform 35 and build-tools 35.0.0.' >&2; exit 1; }
gradle --version | grep -q '^Gradle 8.11.1$' || { echo 'Select Gradle 8.11.1 for this pinned build.' >&2; exit 1; }
exec gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug "$@"
