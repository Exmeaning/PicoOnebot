#!/usr/bin/env bash
set -eu

src="${1:-/var/lib/waydroid/overlay/system}"
here="$(cd "$(dirname "$0")" && pwd)"
dst="$here/libndk/system"

[ -f "$src/lib/libndk_translation.so" ] || {
  echo "找不到 $src/lib/libndk_translation.so" >&2
  echo "先按 docs/env/waydroid-setup.md 安装 libndk,或把 Waydroid overlay/system 路径作为参数传入。" >&2
  exit 1
}
[ -d "$src/lib/arm" ] || { echo "找不到 32 位 ARM 运行库: $src/lib/arm" >&2; exit 1; }

mkdir -p "$dst/bin" "$dst/lib"
cp -a "$src/lib/arm" "$dst/lib/"
cp -a "$src/lib/"libndk_translation*.so "$dst/lib/"
cp -a "$src/bin/arm" "$dst/bin/"
cp -a "$src/bin/"ndk_translation_program_runner* "$dst/bin/"

echo "libndk 构建上下文已准备到 $dst (被 .gitignore 排除)"
