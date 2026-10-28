#!/usr/bin/env bash
# ai-ubuntu 重装前的 P0 备份（小文件，全部打进一个 tar.gz）
# 用法：bash backup-ai-ubuntu.sh [输出目录，默认 ~/backup]
set -euo pipefail

OUT="${1:-$HOME/backup}"
STAMP="$(date +%F-%H%M)"
WORK="$OUT/ai-ubuntu-p0-$STAMP"
mkdir -p "$WORK"

echo "==> 1/6 数据库 dump（ai-ubuntu 上是测试库：1 用户 / 0 词）"
if docker ps --format '{{.Names}}' | grep -q teachloop-db-1; then
  docker exec teachloop-db-1 pg_dump -U teachloop teachloop | gzip > "$WORK/teachloop-db.sql.gz" || true
else
  echo "  跳过：没有 teachloop-db-1"
fi

echo "==> 2/6 部署配置（.env 里是 tunnel token 与 DB 密码）"
mkdir -p "$WORK/teachloop"
for f in .env docker-compose.yml nginx.conf; do
  [ -f "$HOME/teachloop/$f" ] && cp "$HOME/teachloop/$f" "$WORK/teachloop/"
done

echo "==> 3/6 SSH / 服务定义 / Caddy"
mkdir -p "$WORK/ssh" "$WORK/systemd" "$WORK/caddy"
cp "$HOME/.ssh/authorized_keys" "$WORK/ssh/" 2>/dev/null || true
cp "$HOME/.ssh/id_ed25519" "$HOME/.ssh/id_ed25519.pub" "$WORK/ssh/" 2>/dev/null || true
chmod 600 "$WORK/ssh/"* 2>/dev/null || true
sudo cp /etc/systemd/system/strata.service "$WORK/systemd/" 2>/dev/null || true
sudo systemctl list-unit-files --no-pager | grep -E 'strata|comfy|caddy' > "$WORK/systemd/units.txt" 2>/dev/null || true
cp "$HOME/ai/Caddyfile" "$HOME/ai/caddy-password.txt" "$WORK/caddy/" 2>/dev/null || true
tar czf "$WORK/caddy/config.tgz" -C "$HOME" .config/caddy 2>/dev/null || true

echo "==> 4/6 登录态与工程说明"
mkdir -p "$WORK/auth"
for d in .opencode .dsh .gemini; do
  [ -d "$HOME/$d" ] && tar czf "$WORK/auth/${d#.}.tgz" -C "$HOME" "$d" 2>/dev/null || true
done
cp "$HOME/ai/README.md" "$WORK/" 2>/dev/null || true

echo "==> 5/6 环境快照（重建时对照用）"
{
  echo "# 生成于 $(date -Is) on $(hostname)"
  echo; echo "## OS"; cat /etc/os-release | head -3; uname -r
  echo; echo "## GPU"; lspci | grep -i vga
  echo; echo "## venv"; "$HOME/ai/comfy/venv/bin/python" -c 'import torch;print(torch.__version__, torch.version.hip, torch.cuda.is_available(), torch.cuda.get_device_name(0))' 2>/dev/null || true
  echo; echo "## ComfyUI"; git -C "$HOME/ai/ComfyUI" log --oneline -1 2>/dev/null
  echo; echo "## 模型"; find "$HOME/ai/ComfyUI/models" -name '*.safetensors' -size +100M -printf '%s %p\n' 2>/dev/null | sort -k2
  echo; echo "## apt"; dpkg --get-selections | awk '$2=="install"{print $1}'
  echo; echo "## pip"; "$HOME/ai/comfy/venv/bin/pip" freeze 2>/dev/null | grep -iE '^(torch|torchvision|numpy|pillow|transformers|safetensors|accelerate)' || true
  echo; echo "## 端口"; ss -ltnp 2>/dev/null | head -20
  echo; echo "## 大目录"; du -sh "$HOME/ai" "$HOME/Documents/deepseek-harness" 2>/dev/null
} > "$WORK/environment.txt"

echo "==> 6/6 打包"
tar czf "$OUT/ai-ubuntu-p0-$STAMP.tgz" -C "$OUT" "ai-ubuntu-p0-$STAMP"
rm -rf "$WORK"
ls -lh "$OUT/ai-ubuntu-p0-$STAMP.tgz"
echo "完成：把该文件拷到别的机器（含密钥与 .env，别进 git）"
