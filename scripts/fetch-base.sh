#!/usr/bin/env bash
# 拉取宿主 APK 并生成编译期依赖。
# 产物:app/mixin/source.apk、buildtools/gen-dep/raw.jar、app/libs/source.jar
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
URL="${PICO_BASE_APK_URL:-https://download.wearstore.asia/APK/QQ%E6%89%8B%E8%A1%A8%E7%89%88%209.0.7%20%28Android%204.4%20%2B%29.apk}"
SHA="${PICO_BASE_APK_SHA256:-cbf5f4373c4eca3a12fb4d18106de5760d62bffc68f4aa1ebf25537d787e92ab}"
DEX2JAR="${DEX2JAR:-d2j-dex2jar.sh}"   # https://github.com/pxb1988/dex2jar (dex-tools)

mkdir -p "$HERE/app/mixin" "$HERE/buildtools/gen-dep" "$HERE/app/libs"
chmod +x "$HERE/gradlew" 2>/dev/null || true

apk="$HERE/app/mixin/source.apk"
if [ ! -f "$apk" ]; then
  echo "[fetch-base] downloading base apk"
  curl -fL --retry 3 -o "$apk" "$URL"
fi
if [ -n "$SHA" ]; then
  echo "$SHA  $apk" | sha256sum -c -
fi

raw="$HERE/buildtools/gen-dep/raw.jar"
if [ ! -f "$raw" ]; then
  command -v "$DEX2JAR" >/dev/null || { echo "需要 dex2jar(dex-tools),设 DEX2JAR 指向 d2j-dex2jar.sh"; exit 1; }
  echo "[fetch-base] dex2jar → raw.jar"
  "$DEX2JAR" --force -o "$raw" "$apk"
fi

echo "[fetch-base] gen-dep → app/libs/source.jar"
(cd "$HERE" && ./gradlew -q :gen-dep:run)
ls -la "$HERE/app/libs/source.jar"
