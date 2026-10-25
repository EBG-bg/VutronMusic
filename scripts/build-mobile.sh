#!/usr/bin/env sh
set -eu
repository=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
configuration=${1:-Debug}
case "$configuration" in Debug|Release) ;; *) printf '%s\n' 'Usage: sh scripts/build-mobile.sh [Debug|Release]' >&2; exit 2 ;; esac
cd "$repository/apps/android-vutron"
if [ ! -d node_modules ]; then npm ci --ignore-scripts; fi
node node_modules/typescript/bin/tsc --noEmit
npm run build
npm exec -- cap sync android
cd android
sh ./gradlew ":app:assemble$configuration" --console=plain
