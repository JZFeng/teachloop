#!/usr/bin/env bash
# 给 docs/mock/brand-2026-10-22 出评审截图。
#
# 为什么用 Chrome headless 而不是手工截图：样张有 4 屏 × 2 主题 × 状态，
# 手工点一遍既慢又会漏；这里用 URL 参数固定每一张的条件，任何人重跑得到同一组图。
#
# 依赖：本机 Google Chrome（macOS 默认路径，可用 CHROME=... 覆盖）。
# 用法：bash scripts/brand/shoot-mock.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
PAGE="file://$ROOT/docs/mock/brand-2026-10-22/index.html"
OUT="$ROOT/docs/reports/brand-mock-2026-10-22"
PROFILE="$ROOT/.cache/chrome-headless"

[ -x "$CHROME" ] || { echo "[shoot-mock] 找不到 Chrome：$CHROME" >&2; exit 1; }
mkdir -p "$OUT" "$PROFILE"

shot() {
  local name="$1" query="$2" size="$3"
  "$CHROME" --headless=new --no-sandbox --disable-gpu --disable-crash-reporter --disable-breakpad \
    --no-first-run --user-data-dir="$PROFILE" --hide-scrollbars \
    --force-device-scale-factor=2 --window-size="$size" --virtual-time-budget=1600 \
    --screenshot="$OUT/$name.png" "$PAGE?$query&chrome=0" >/dev/null 2>&1
  printf '  %-26s %s\n' "$name.png" "$size"
}

# 产品主视图（不带预览控制条）
shot login-light      "screen=login&theme=light&state=default"            "1440,900"
shot login-dark       "screen=login&theme=dark&state=default"             "1440,900"
shot login-error      "screen=login&theme=light&state=error"              "1440,900"
shot papers-light     "screen=papers&theme=light&state=data&intensity=brand"    "1440,900"
shot papers-dark      "screen=papers&theme=dark&state=data&intensity=brand"     "1440,900"
shot papers-calm      "screen=papers&theme=light&state=data&intensity=calm"     "1440,900"
shot papers-empty     "screen=papers&theme=light&state=empty"             "1440,900"
shot papers-error     "screen=papers&theme=light&state=error"             "1440,900"
shot papers-loading   "screen=papers&theme=light&state=loading"           "1440,900"
shot new-light        "screen=new&theme=light&state=default"              "1440,1750"
shot new-mismatch     "screen=new&theme=light&state=mismatch"             "1440,1750"
shot brand-light      "screen=brand&theme=light"                          "1440,3000"
shot brand-dark       "screen=brand&theme=dark"                           "1440,3000"
# 预览控制条长什么样（带 chrome=1）
"$CHROME" --headless=new --no-sandbox --disable-gpu --disable-crash-reporter --disable-breakpad \
  --no-first-run --user-data-dir="$PROFILE" --hide-scrollbars \
  --force-device-scale-factor=2 --window-size=1440,900 --virtual-time-budget=1600 \
  --screenshot="$OUT/overview-controls.png" "$PAGE?screen=papers&theme=light&state=data&intensity=brand&chrome=1" >/dev/null 2>&1
printf '  %-26s %s\n' "overview-controls.png" "1440,900"

echo "[shoot-mock] 已输出到 docs/reports/brand-mock-2026-10-22/"
