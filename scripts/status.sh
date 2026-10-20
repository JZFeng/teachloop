#!/usr/bin/env bash
# 一眼看清：最近提交、工作区改动、最近测试结果、进度文件里的「当前/剩余」。
set -u
cd "$(dirname "$0")/.."

echo "=== 最近提交 ==="
git log --oneline -5

echo
echo "=== 工作区 ==="
if git diff --quiet && git diff --cached --quiet && [ -z "$(git ls-files --others --exclude-standard)" ]; then
  echo "干净"
else
  git status --short
fi

echo
echo "=== 最近测试（surefire 报告）==="
if compgen -G "teachloop-*/target/surefire-reports/*.txt" > /dev/null; then
  grep -h "Tests run" teachloop-*/target/surefire-reports/*.txt \
    | awk -F'Tests run: ' '{split($2,a,","); t+=a[1]; if (a[2] !~ /Failures: 0/) f++} END {printf "tests=%d  failing_files=%d\n", t, f}'
  ls -t teachloop-*/target/surefire-reports/*.txt 2>/dev/null | head -1 | xargs -I{} stat -f '  last report: %Sm  %N' -t '%Y-%m-%d %H:%M' {} 2>/dev/null || true
else
  echo "未跑过测试"
fi

echo
echo "=== 进度 ==="
if [ -f docs/PROGRESS.md ]; then
  sed -n '/^## 当前/,/^## 剩余/p' docs/PROGRESS.md | sed '$d'
  sed -n '/^## 剩余/,$p' docs/PROGRESS.md
else
  echo "还没有 docs/PROGRESS.md"
fi
