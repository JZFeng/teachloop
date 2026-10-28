# 生图机恢复手册（ai-ubuntu）

> 用途：换主板 / 换 CPU / 重装系统后，任意 agent 照本手册可以在 **30–60 分钟**内把
> FLUX 出图环境恢复到「能批量出 113 张词卡配图」的状态。
> 最后验证：2026-10-27，109 张/113 张实测 2.43s/张。
> 显卡不变：AMD Radeon RX 7900 XTX 24GB（gfx1100）。

---

## 0. 验收标准（做完必须能通过）

```bash
# 1) 服务能起
curl -s http://127.0.0.1:8188/system_stats | head -c 120

# 2) 单张出图（512×512，4 步）< 10 秒
~/ai/comfy/venv/bin/python /tmp/smoke_one.py     # 脚本见 §7

# 3) 批量 113 张 < 6 分钟，产出 176px WebP
~/ai/comfy/venv/bin/python scripts/vocab/image/batch_images_flux.py \
  --scenes /tmp/scenes113_drawable.json --out ~/ai/vocab-images/final2
python3 scripts/vocab/image/normalize_images.py \
  --input ~/ai/vocab-images/final2 --output ~/ai/vocab-images/final2_176 --size 176 --format webp

# 4) 质量指标（88px）：亮度 190–210、对比度 ≥52、近白像素 <1%
```

---

## 1. 硬件与系统

| 项 | 要求 | 本机实测 |
| :--- | :--- | :--- |
| GPU | AMD RX 7900 XTX 24GB（gfx1100），PCIe x16 | `03:00.0 VGA … Navi 31`，VRAM 25753026560 B |
| CPU / 内存 | ≥8 核 / ≥32GB | i5-10400F（本次更换）/ 62GB |
| 磁盘 | ≥120GB 空闲（模型 ≈55GB） | NVMe 457GB，184GB 空闲 |
| OS | Ubuntu 24.04 LTS（内核 ≥6.8） | Ubuntu 24.04.5，内核 7.0.0-38 |
| 用户组 | `render` + `video`（访问 `/dev/kfd`、`/dev/dri`） | `id -nG` 含 render、video |

**BIOS 必开**：Above 4G Decoding、Resizable BAR（AMD 卡跑大模型必须，否则显存映射异常）。

**不需要单独装 ROCm 用户态**：PyTorch 的 `+rocm7.2` wheel 自带 ROCm 运行时库，
本机 `/opt/rocm` 不存在也能跑（已验证 `torch.cuda.is_available() == True`）。
只需要内核的 `amdgpu` 驱动 + `render`/`video` 组权限。

---

## 2. 目录约定（必须一致，脚本写死了这些路径）

```
/home/jzfeng/ai/
├── ComfyUI/                 # git c06bf5f (0.41.0)
│   ├── models/
│   │   ├── checkpoints/     # flux1-schnell / sd_xl_base / DreamShaper
│   │   ├── diffusion_models/# flux 的 symlink 放这里（UNETLoader 只扫这个目录）
│   │   ├── text_encoders/   # t5xxl_fp8_e4m3fn / clip_l
│   │   ├── vae/             # ae.safetensors
│   │   └── loras/           # vector-illustration
│   └── main.py
├── comfy/venv/              # python 3.12.3 + torch 2.14.1+rocm7.2
├── bin/                     # 静态 ffmpeg/ffprobe（可选）
├── logs/
└── vocab-images/            # 出图产物：raw / 176 WebP / 96 WebP
```

---

## 3. 从零安装（约 20 分钟，不含下载）

```bash
# 3.1 系统包
sudo apt-get update
sudo apt-get install -y git curl aria2 python3.12-venv python3-pip build-essential ffmpeg

# 3.2 venv（本机没有 uv，用标准 venv）
python3 -m venv ~/ai/comfy/venv
source ~/ai/comfy/venv/bin/activate

# 3.3 PyTorch（ROCm 7.2 wheel，自带 ROCm 运行时）
pip install --upgrade pip
pip install torch torchvision --index-url https://download.pytorch.org/whl/rocm7.2
python -c "import torch; print(torch.__version__, torch.version.hip, torch.cuda.is_available())"
# 期望：2.14.1+rocm7.2 7.2.xxxxx True

# 3.4 ComfyUI（钉住验证过的 commit）
git clone https://github.com/comfyanonymous/ComfyUI ~/ai/ComfyUI
cd ~/ai/ComfyUI && git checkout c06bf5f
pip install -r requirements.txt

# 3.5 GPU 权限（若不在组里）
sudo usermod -aG render,video "$USER"   # 之后重新登录或重启
```

> 如果 `torch.cuda.is_available()` 为 False：先查 `ls -l /dev/kfd`（应 root:render 660）、
> `id -nG | grep render`；再查 `sudo dmesg | grep -i amdgpu | tail`。

---

## 4. 模型清单（≈55GB）

| 文件 | 大小 | 放到 | 来源 | 是否需要许可 |
| :--- | ---: | :--- | :--- | :--- |
| `flux1-schnell.safetensors` | 23.8GB | `checkpoints/` + symlink 到 `diffusion_models/` | `black-forest-labs/FLUX.1-schnell` | **是（gated，要在网页点 Agree）** |
| `ae.safetensors` | 335MB | `checkpoints/` 与 `vae/` 各一份 | 同上 | 是 |
| `t5xxl_fp8_e4m3fn.safetensors` | 4.9GB | `text_encoders/` | `comfyanonymous/flux_text_encoders` | 否 |
| `clip_l.safetensors` | 246MB | `text_encoders/` | 同上 | 否 |
| `sd_xl_base_1.0.safetensors` | 6.9GB | `checkpoints/` | `stabilityai/stable-diffusion-xl-base-1.0` | 否 |
| `DreamShaperXL_Turbo_v2_1.safetensors` | 6.5GB | `checkpoints/` | `Lykon/dreamshaper-xl-v2-turbo` | 否 |
| `vector-illustration.safetensors`（LoRA） | 218MB | `loras/` | `Muapi/vector-illustration` | 否 |

下载命令（**用 aria2 多线程，单线程会被限速到 1.5MB/s**）：

```bash
export HF_TOKEN=hf_xxx          # 只读 token；gated 仓库必须先在网页点 Agree
cd ~/ai/ComfyUI/models

aria2c -x16 -s16 -k 1M --file-allocation=none \
  -H "Authorization: Bearer $HF_TOKEN" \
  -o checkpoints/flux1-schnell.safetensors \
  "https://huggingface.co/black-forest-labs/FLUX.1-schnell/resolve/main/flux1-schnell.safetensors"

aria2c -x8 -s8 -H "Authorization: Bearer $HF_TOKEN" -o checkpoints/ae.safetensors \
  "https://huggingface.co/black-forest-labs/FLUX.1-schnell/resolve/main/ae.safetensors"

curl -L -H "Authorization: Bearer $HF_TOKEN" -o text_encoders/t5xxl_fp8_e4m3fn.safetensors \
  "https://huggingface.co/comfyanonymous/flux_text_encoders/resolve/main/t5xxl_fp8_e4m3fn.safetensors"
curl -L -H "Authorization: Bearer $HF_TOKEN" -o text_encoders/clip_l.safetensors \
  "https://huggingface.co/comfyanonymous/flux_text_encoders/resolve/main/clip_l.safetensors"

curl -L -o loras/vector-illustration.safetensors \
  "https://huggingface.co/Muapi/vector-illustration/resolve/main/vector-illustration.safetensors"

# 关键收尾（缺一步 Flux 就加载不到）
ln -sf ~/ai/ComfyUI/models/checkpoints/flux1-schnell.safetensors \
       ~/ai/ComfyUI/models/diffusion_models/flux1-schnell.safetensors
cp ~/ai/ComfyUI/models/checkpoints/ae.safetensors ~/ai/ComfyUI/models/vae/ae.safetensors
```

**校验**：`flux1-schnell.safetensors` 必须正好 `23782506688` 字节，其他值说明没下完。

---

## 5. 启动 ComfyUI（参数不能少）

```bash
cd ~/ai/ComfyUI
nohup ~/ai/comfy/venv/bin/python main.py \
  --listen 127.0.0.1 --port 8188 \
  --reserve-vram 1 \
  --fp8_e4m3fn-unet \
  > ~/ai/logs/comfyui-vocab.log 2>&1 </dev/null &
disown
```

| 参数 | 为什么必须 |
| :--- | :--- |
| `--fp8_e4m3fn-unet` | FLUX fp16 权重 23.8GB，24GB 卡直接塞会 OOM；fp8 载入后约 12GB |
| `--reserve-vram 1` | 给系统/桌面留 1GB，避免边缘 OOM |
| **不要** `--cache-none` | 每次请求重载 24GB 模型，单张从 2.4s 变成 8.5s |
| `--listen 127.0.0.1` | 只本机；需要局域网时用 Caddy 反代加密码 |

---

## 6. 显存互斥（最容易踩的坑）

同机 `strata.service`（本地 LLM，Qwen3.8-Flash-Next IQ2_XS）常驻 **≈24.2GB / 24.5GB** 显存，
ComfyUI 一启动就 `Got an OOM, unloading all loaded models`。

```bash
# 出图前
sudo systemctl stop strata
for f in /sys/class/drm/card*/device/mem_info_vram_used; do echo "$(( $(cat $f)/1024/1024 )) MB"; done   # 期望 <100MB

# 出图后（用户在用它时再起）
sudo systemctl start strata
```

**不要**试图让两者共存：单卡 24GB 放不下（LLM 24GB + FLUX fp8 12GB）。

---

## 7. 出图流程（三步）

```bash
# ① 场景提示词（中文 mentalImage → 英文可画场景）
LLM_API_KEY=... python3 scripts/vocab/image/make_scene_prompts.py \
  --sqlite .envs/qa/content-delta.db --output /tmp/scenes.json

# ② 批量出图（FLUX 512² / 4 步 / 水彩暖底）
python3 scripts/vocab/image/batch_images_flux.py \
  --scenes /tmp/scenes.json --out ~/ai/vocab-images/raw

# ③ 归一化 + 两档尺寸（176=卡片 2x，96=1x）
python3 scripts/vocab/image/normalize_images.py --input ~/ai/vocab-images/raw \
  --output ~/ai/vocab-images/176 --size 176 --format webp
python3 scripts/vocab/image/normalize_images.py --input ~/ai/vocab-images/raw \
  --output ~/ai/vocab-images/96 --size 96 --format webp
```

单张冒烟脚本（`/tmp/smoke_one.py`，可直接内联）：

```python
import json, uuid, urllib.request
API="http://127.0.0.1:8188"
wf={"u":{"class_type":"UNETLoader","inputs":{"unet_name":"flux1-schnell.safetensors","weight_dtype":"default"}},
    "c":{"class_type":"DualCLIPLoader","inputs":{"clip_name1":"t5xxl_fp8_e4m3fn.safetensors","clip_name2":"clip_l.safetensors","type":"flux"}},
    "v":{"class_type":"VAELoader","inputs":{"vae_name":"ae.safetensors"}},
    "5":{"class_type":"EmptySD3LatentImage","inputs":{"width":512,"height":512,"batch_size":1}},
    "6":{"class_type":"CLIPTextEncode","inputs":{"text":"warm-toned watercolor illustration on textured paper: a climber on a rock wall","clip":["c",0]}},
    "7":{"class_type":"FluxGuidance","inputs":{"conditioning":["6",0],"guidance":3.5}},
    "3":{"class_type":"KSampler","inputs":{"seed":1,"steps":4,"cfg":1.0,"sampler_name":"euler","scheduler":"simple","denoise":1.0,"model":["u",0],"positive":["7",0],"negative":["7",0],"latent_image":["5",0]}},
    "8":{"class_type":"VAEDecode","inputs":{"samples":["3",0],"vae":["v",0]}},
    "9":{"class_type":"SaveImage","inputs":{"filename_prefix":"smoke","images":["8",0]}}}
r=urllib.request.Request(API+"/prompt",data=json.dumps({"prompt":wf,"client_id":str(uuid.uuid4())}).encode(),headers={"Content-Type":"application/json"})
print(json.loads(urllib.request.urlopen(r).read())["prompt_id"])
```

---

## 8. 质量基线（改参数后必须重新量）

| 指标（88px） | 目标 | 实测（113 张） |
| :--- | :--- | :--- |
| 亮度 | 190–210 | 196.0 |
| 对比度（std） | ≥52 | 54.8 |
| 近白像素（≥250 占比） | <1% | 0.4% |

- 后处理曲线：levels 2%–98% → 暗部 `×0.92+0.06` → 高光压 `×0.965`（≈246）→ 暖色 `R×1.03 / B×0.97` → **缩到目标尺寸后**再 `UnsharpMask(0.8, 30, 2)`。
- **顺序不能反**：先锐化后缩小会把纸纹放大成颗粒（实测 percent 55 时最明显）。
- 对比图/截图统一字体 `Hiragino Sans GB`（Arial 无中文字形，会显示方框）。

---

## 9. 故障排查

| 症状 | 原因 | 修法 |
| :--- | :--- | :--- |
| `Got an OOM, unloading all loaded models` | strata 占满显存 | `sudo systemctl stop strata` |
| `clip input is invalid: None` | 用了官方 BFL 整合包当 checkpoint；它其实是 **UNET-only** | 改 `UNETLoader` + `DualCLIPLoader(type=flux)` + `VAELoader` |
| UNETLoader 列表里没有 flux | 文件在 `checkpoints/`，UNETLoader 只扫 `diffusion_models/` | 建 symlink（§4 收尾） |
| `error: ambiguous option: --fp8_e4m3fn` | 有两个同前缀参数 | 显式写 `--fp8_e4m3fn-unet` |
| 下载 403 | gated 仓库未接受许可 | 网页点 Agree，再带 `Authorization: Bearer` 请求 |
| 下载只有 1.5MB/s | 单连接被限速 | `aria2c -x16 -s16` |
| 模型存在但加载失败 | 半截文件（Ctrl-C 中断） | 删掉重下，校验字节数 |
| 出图 8s+ 而不是 2.4s | 加了 `--cache-none` | 去掉该参数 |
| 图有 “Neverwhite” 之类水印文字 | 模型自发签名 | 提示词加 `no watermark, no signature, no text` |
| 中文标签变方框 | 用了无 CJK 字形的字体 | 换 `Hiragino Sans GB` |

---

## 10. 时间与空间预算

| 步骤 | 时间 | 空间 |
| :--- | ---: | ---: |
| apt + venv + torch + ComfyUI | ≈15 分钟 | ≈15GB |
| 模型下载（55GB @30MB/s） | ≈30 分钟 | 55GB |
| 113 张出图（2.43s/张） | ≈5 分钟 | 39MB（512 PNG） |
| 归一化 + 176/96 WebP | <10 秒 | 1.6MB |
| **合计** | **≈50 分钟** | **≈70GB** |

---

## 11. 收尾 checklist

- [ ] `nvidia-smi` 不存在是正常的（A 卡）；`rocm-smi` 也不必须
- [ ] `torch.cuda.is_available() == True` 且设备名 `AMD Radeon RX 7900 XTX`
- [ ] `/dev/kfd` 权限 `root:render 660`，用户在 `render`、`video` 组
- [ ] `flux1-schnell.safetensors` 字节数 = 23782506688
- [ ] `diffusion_models/flux1-schnell.safetensors` symlink 存在
- [ ] ComfyUI 启动带 `--fp8_e4m3fn-unet`（不带会 OOM）
- [ ] `strata.service` 已停（否则必 OOM）
- [ ] 单张 <10s，113 张 <6min
- [ ] 88px 指标：L 190–210、SD ≥52、白底 <1%
- [ ] 出图完成后按需 `sudo systemctl start strata`

---

## 12. 重装前备份清单（2026-10-27 实测）

`bash scripts/ops/backup-ai-ubuntu.sh` 会自动打一个 ≈22KB 的 P0 包（数据库 dump、`~/teachloop/.env`、
SSH 密钥、`strata.service`、Caddy 配置、`~/.dsh` 凭据、环境快照）。**含密钥，不要进 git。**

必须单独搬走的大件（体积大、只在 ai-ubuntu 上有）：

| 目录 | 大小 | 丢了会怎样 |
| :--- | ---: | :--- |
| `~/Documents/deepseek-harness/default-workspace/Strata-data` | **73GB** | 本地 LLM 权重/专家包，重下很久 |
| `~/Documents/deepseek-harness/default-workspace/Strata` | 12GB | 代码可 clone，venv 可重建 |
| `~/ai/ComfyUI/models` | 55GB | 出图模型（2026-10-27 已备份到另一台机器） |
| `~/ai/vocab-images` | 409MB | 113 词各版本出图产物 |
| `~/ai/teaching` | 184MB | 教学脚本 |

不用备份：`~/ai/comfy/venv`（17GB，bootstrap 重建）、`~/ai/ComfyUI` 代码部分（git clone）、
docker images（源码 build）、`~/.cache`。

重装后恢复顺序：OS → docker → `~/teachloop`（放回 `.env`/compose/nginx）→
`docker compose up -d` → `gunzip < teachloop-db.sql.gz | docker exec -i teachloop-db-1 psql -U teachloop teachloop`
→ 模型拷回 → `bash scripts/vocab/image/bootstrap_image_box.sh`。
