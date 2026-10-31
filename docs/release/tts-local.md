# 本机 Piper 发音（dev）— 安装、启动与排障（2026-10-31）

**一句话**：dev 以前**从来没有**跑过 Piper sidecar——`scripts/dev.sh` 里没有 `piper`/`5002`，后端每次点喇叭都 404 回落浏览器语音；所以「TTS 尾音杂音」和「inflect 重音不对」在 dev 上听到的**都不是 Piper**。现在 `dev.sh` 会在引擎+模型就绪时自动拉起 sidecar，缺依赖只打一条明确提示；前端回落也改成显式挑 Google 音色。

> 关联上报：items 6（TTS 尾音杂音，用户认为 regression）、item 7（inflect 重音在第一个音节，而音标是 /ɪnˈflekt/）。
> 相关提交：`7ac0945`（T410 修 TTS 尾音噪音，**只覆盖 Piper 的 WAV 链路**）、`2aa99bd`（T438 浏览器回落语速降到 0.9）、`0525e71`（T427 英美双音色）、`f2cd805`（例句朗读端点）。

## 1. 链路与文件

```
浏览器点喇叭 (web/src/vocab/SpeakerButton.tsx)
  └─ GET /api/vocab/audio?form=…&accent=uk|us
       └─ TtsAudioEndpoint.java:158-174  cacheOrSynthesize()
            ├─ 命中 BlobStore 缓存 → 200 audio/wav（uploads/vocab/tts/<key>.wav）
            ├─ 未命中 → PiperTtsEngine.java:32-52 → POST http://127.0.0.1:5002/synthesize
            │      └─ docker/piper-sidecar.py → piper CLI → WAV
            └─ 引擎失败 → 404（TtsAudioEndpoint.java:170-172 打一条 WARN）
  └─ 404 / fetch 失败 → 浏览器 speechSynthesis 回落（SpeakerButton.tsx:179-226）
```

- 后端把 sidecar 地址写死在 `application.yml:126-127`：`engine: piper`、`engine-url: http://127.0.0.1:5002`。
- 容器里的启动方式：`docker/entrypoint.sh:3-5`（`RUN_TTS_SIDECAR=true` 时起 sidecar）+ `Dockerfile:28-42`（装 piper-tts 与两个模型）。**dev 一直缺这一段**。
- 缓存键包含词形 + provider + 音色 + 语速（`TtsAudioEndpoint.java:213-220`），改语速会重新合成，不会拿到旧音频。

## 2. 安装（macOS）

```bash
bash scripts/setup-tts.sh          # 引擎 + 英美两个模型（约 120MB）
bash scripts/setup-tts.sh --all    # 额外装中文/西语音色（例句朗读用，各约 60MB）
bash scripts/setup-tts.sh --print  # 只打印将要做什么
bash scripts/setup-tts.sh --serve  # 装好后前台起 sidecar（dev 已经在跑时用它，不用重启 dev）
```

装到仓库内、已被 git 忽略的 `.dsh/tts/`：

| 路径 | 内容 |
|---|---|
| `.dsh/tts/venv` | piper-tts（独立 venv，避免污染系统 Python） |

> **模型与 venv 都不进仓库**：整个 `.dsh/tts/`（约 500MB）在 `.gitignore` 里；新 clone / 换机器必须重跑 `bash scripts/setup-tts.sh` 才有本地发音，否则 dev 会（明确提示后）回落浏览器语音。
| `.dsh/tts/models/` | `en_US-amy-medium.onnx(.json)`、`en_GB-alan-medium.onnx(.json)` |
| `.dsh/tts/uv-cache`、`.dsh/tts/python` | 用 uv 时下载的缓存与 Python 3.12 |

要点：

- **不需要 `brew install espeak-ng`**：piper-tts 自带 espeak-ng 数据（`site-packages/piper/espeak-ng-data`），`setup-tts.sh` 结尾会用它对 `inflect` 做一次音素化自检。
- **Python 版本**：本机系统 Python 是 3.14，`piper-tts`/`onnxruntime` 的 wheel 不一定有；脚本优先用 `uv`（`brew install uv`）拉一个 3.12 建 venv，没有 uv 才退回 `python3 -m venv`。
- 模型来自 `https://huggingface.co/rhasspy/piper-voices`，脚本可重复执行（已存在的文件不重下）。

## 3. 启动 / 停止 / 状态

```bash
# 正常路径：dev.sh 自动拉起并随 dev 一起清理
make dev

# 现状：dev 已经在跑（8010/5173 不动），只补起 sidecar
bash scripts/setup-tts.sh --serve      # 前台，Ctrl-C 停止
# 或者受管后台：
PIPER_BIN="$PWD/.dsh/tts/venv/bin/piper" \
PIPER_MODEL="$PWD/.dsh/tts/models/en_US-amy-medium.onnx" \
PIPER_MODEL_DIR="$PWD/.dsh/tts/models" PIPER_DEFAULT_VOICE=en_US-amy-medium PIPER_PORT=5002 \
  python3 docker/piper-sidecar.py >> .dsh/logs/dev-piper.log 2>&1 &

# 状态 / 停止
curl -s http://127.0.0.1:5002/health
lsof -nP -iTCP:5002 -sTCP:LISTEN
kill "$(lsof -ti tcp:5002)"
```

`dev.sh` 的行为（`scripts/dev.sh:76-136`）：

- 找引擎：`$PIPER_BIN` → `.dsh/tts/venv/bin/piper` → `PATH` 里的 `piper`；
- 找模型：`$PIPER_MODEL_DIR`（默认 `.dsh/tts/models`）里的 `<US_VOICE>.onnx`；
- 端口：默认 5002，可从 `VOCAB_TTS_ENGINE_URL` 反推，`VOCAB_TTS_PORT` 可覆盖；
- 5002 已被占用 → 复用并提示（**不会**去杀别人的进程）；
- 缺引擎/模型/python3 → 打一条明确的黄色提示（指向本文件与 `setup-tts.sh`），dev 照常启动；
- 退出时只 kill 自己在 `tts_sidecar_up` 里起的那个 PID（`scripts/dev.sh:141-143`），不扫端口。

## 4. 验证（可复核）

```bash
# ① sidecar 自身
curl -s http://127.0.0.1:5002/health
# {"status":"ok","model":"…/en_US-amy-medium.onnx","piper":"…/.dsh/tts/venv/bin/piper"}

curl -s -o /tmp/tts.wav -X POST http://127.0.0.1:5002/synthesize \
  -H 'Content-Type: application/json' \
  -d '{"text":"inflect","voice":"en_US-amy-medium","rate":0.9}'
file /tmp/tts.wav   # RIFF (little-endian) data, WAVE audio, Microsoft PCM, 16 bit, mono 22050 Hz

# ② 后端端点（需要登录态；无 cookie 是 401，不是 500）
curl -s -c /tmp/tl.txt -X POST http://127.0.0.1:8010/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"student@teachloop.local","password":"teachloop123"}'
curl -s -b /tmp/tl.txt -o /tmp/api.wav \
  -w 'http=%{http_code} type=%{content_type} bytes=%{size_download}\n' \
  'http://127.0.0.1:8010/api/vocab/audio?form=inflect&accent=us'

# ③ 结尾/时长机械判据（不需要耳朵）
python3 - <<'PY'
import wave, struct, math
with wave.open('/tmp/tts.wav','rb') as w:
    n, sr = w.getnframes(), w.getframerate()
    s = struct.unpack('<%dh' % n, w.readframes(n))
win = int(sr * 0.008)
print("dur", round(n/sr,3), "frame", sr, "peak_last8ms", max(abs(x) for x in s[-win:]), "last8", s[-8:])
PY
```

实测（2026-10-31，本机）：

- sidecar `/synthesize`：`http=200`、`audio/wav`、0.78–0.99s（后端超时 2s，`application.yml:142`）。
- 后端端点：带 cookie `200 audio/wav` 37420 字节（`inflect`/美音），第二次命中缓存、字节完全一致、带 `ETag` 与 `immutable`；无 cookie `401`（前端 `fetch` 带 `credentials: 'include'`，正常路径不会 401）。
- 结尾：`soften_tail()` 之前最后 8 个采样 `(-11,-29,-34,-23,-5,2,0,3)`、8ms 峰值 104；之后 `(0,0,0,0,0,0,0,0)`、峰值 39（在淡出窗口内单调衰减），文件长度不变、仍是有合法 RIFF 的 WAV。
- 页面入口：查词页 `/vocab/words/by-form/inflect` 点「美音发音」→ 网络 `/api/vocab/audio?form=inflect&accent=us&v=2` 返回 **200 audio/wav**，`Audio.play()` resolve，**没有**走 `speechSynthesis`；没有 404。

## 5. items 6/7 的根因档案

| 证据 | 内容 | 复核命令 |
|---|---|---|
| dev 没有 sidecar | `lsof -nP -iTCP:5002` 空；`scripts/dev.sh` 里 grep 不到 `piper`/`5002`（改动前） | `git log -p -- scripts/dev.sh`、`lsof` |
| 后端 20 次失败 | `.dsh/logs/dev-backend.log` 里 20 条 `TTS unavailable: null`，首条 10:23:46.153、末条 10:29:50.857 | `grep -c "TTS 不可用" .dsh/logs/dev-backend.log` |
| 没有缓存可命中 | 改动前 `uploads/vocab/tts/` 为空目录 → 20 次全是真合成失败，不是缓存脏数据 | `ls uploads/vocab/tts` |
| 失败原因 | `PiperTtsEngine.java:49-51` 把 `ConnectException` 包成 `TTS unavailable: <message>`，而 `ConnectException.getMessage()` 是 `null` | 日志原文 |
| 用户当时在 dev | `inflect` 词条创建时间 `2026-10-31 10:10:14`（PDT），比第一条失败早 13 分钟；qa 后端日志里 `TTS 不可用` 0 条 | `docker exec teachloop-pg psql -U teachloop -d teachloop -c "select form, created_at at time zone 'America/Los_Angeles' from vocab_words where form='inflect';"` |
| 7ac0945 修的是什么 | 它只改 Piper 的 CONTENT_TYPE/缓存键 `.wav`、stub 的 RIFF、前端 object URL 释放——**dev 从来没跑到这些代码** | `git show 7ac0945 --stat` |
| T438 改了什么 | `2aa99bd` 把浏览器回落的 `rate` 设为 `0.9`、加 `synth.cancel()`、保留 utterance 引用；T427 按 accent 选 en-GB/en-US | `git show 2aa99bd -- web/src/vocab/SpeakerButton.tsx` |

**为什么用户觉得是 regression**：`7ac0945` 修的是 Piper 的 WAV 链路，验证发生在容器/生产（`RUN_TTS_SIDECAR=true`）；dev 走的是另一条完全不同的链路（浏览器 SpeechSynthesis），这条链路从来没被那次修复覆盖。而 `T438` 恰好又改了这条链路的语速（1.0→0.9）与打断方式，于是「以前好像好过」的体感对上了「现在又坏了」。

**item 7 的机械证据（没有耳朵，只能到这）**：

- espeak（piper 用的音素化）把 `inflect` 读作 `ɪ n f l ˈ ɛ k t`——重音标记在 `ɛ` 前，即第二音节，**与音标 /ɪnˈflekt/ 一致**：`setup-tts.sh` 结尾的自检就打印这一行。所以「本地引擎把音素化搞错」不成立。
- Piper 音频（`en_US-amy-medium`，rate 0.9）的能量包络：第二音节峰值 1.0、第一音节 0.7；F0 在 0.30s 从 223Hz 抬到 262Hz 再下降——有第二音节的音高突起。
- 对比 macOS 默认美音 `Samantha`（同一个词）：F0 从 193Hz 单调降到 103Hz，第二音节没有音高突起（时长更长 300ms vs 220ms）。**Chrome 不指定 `voice` 时落到哪一个，就是这次的不确定项**（本机 `speechSynthesis.getVoices()` 里 `Samantha` 是 `default: true`）。
- 结论：听到的第一音节重音来自浏览器音色，而不是 Piper；而浏览器音色当时**从未被前端显式选择**（`utterance.voice` 没赋值）。

## 6. 浏览器回落（前端改动，`web/src/vocab/SpeakerButton.tsx`）

1. `pickFallbackVoice()`（`:48-81`）：先找「`lang` 完全匹配 且名字以 `Google` 开头」的音色，其次选 `lang` 完全匹配的**本机**音色，最后才按语言前缀或浏览器默认。**不写死音色名**：Google 音色名跟着浏览器 UI 语言变（英文 `Google US English` / 繁体 `Google 國語（臺灣）` / 西语 `Google español`），写死名字在非简中界面的浏览器上命不中。选中后显式赋给 `utterance.voice`（`:194`），不再让浏览器自己挑。
2. `waitForVoices()`（`:94-114`）：`voiceschanged` 之前 `getVoices()` 可能是空数组，首次点击最多等 800ms。实测本机页面刚加载时 `getVoices().length === 0`，等完之后仍能选中 `Google US English`。
3. `cancelSpeech()`（`:150-156`）：只在 `speechSynthesis.speaking` 为真时 `cancel()`——结束后再 cancel 会在尾音处补一声「咔哒」；`onend/onerror` 里只清引用与状态（`:197-218`）。
4. Google 音色需要联网：`onerror` 且错误不是 `canceled/interrupted` 时，换同语言的**本机**音色重放一次（`:202-218`），避免「回落」变成没声音。
5. 播放中再点一次仍然是「停止」：`stop()` 只暂停/释放 object URL + 清 utterance（`:156-164`）。

实测（ego 浏览器，本机 199 个音色）：

- `pickFallbackVoice(voices,'en-US')` → `Google US English`；`'en-GB'` → `Google UK English Female`。
- 强制后端 404 后点「美音发音」：只发一条 `speechSynthesis.speak`，`voice=Google US English, lang=en-US, rate=0.9`，结束后 `speaking=false`、按钮回到未播放态。
- 强制 404 后点「英音发音」：`voice=Google UK English Female, lang=en-GB`。
- 播放中再点「停止」：`speaking=false`、没有多余 utterance、按钮复位。

> 注意：ego 浏览器的音色表来自这台 macOS，和用户 Chrome 的清单基本同源（Chrome 用系统语音 + Google 语音），但**具体选了哪个音色**只有用户机器的 `speechSynthesis.getVoices()` 能确认。用户可在页面控制台跑：
> `speechSynthesis.getVoices().filter(v => v.lang.startsWith('en-US')).map(v => v.name + (v.default ? ' (default)' : ''))`

## 7. 发音覆盖表（评估过，暂不实施）

如果用户用耳朵确认 **Piper 也把 `inflect` 读成第一音节重音**，才需要覆盖表。先看代价：

- 覆盖表要按 `form` 指定一份音素串（如 `ɪnflˈɛkt`），并把表塞进缓存键（否则旧音频命中缓存）；还要给每个词做一次人工校验，词表会随词库增长。
- piper 1.8 的 CLI/`PiperVoice.synthesize()` 只接受**文本**，音素由 espeak 内部产生；`[[…]]` 音素块在本版本**不支持**（实测 `[[ɪnflˈɛkt]]` 被念成字母表），所以要么改成 in-process 调 piper 并 monkeypatch 音素（sidecar 重写，约 50 行 + 常驻模型内存），要么用「拼写改写」骗过 espeak（实测 `in-flect` 仍得到 `ɪnflˈɛkt`，可行；`in flect` 会变成两个词，不可用）。
- 因此：**本次不做**。当前证据（音素正确、Piper 包络与 F0 都指向第二音节）不支持「本地引擎读错」这个前提。真要做，最小成本是 sidecar 里加一张 `form → 改写文本` 的表（复用 `in-flect` 这类连字符写法），单条验证用 `EspeakPhonemizer().phonemize('en-us', 改写文本)` 比对。

## 8. 残余风险

1. **每请求起一次 piper 进程**：0.78–1.0s，后端超时 2s（`application.yml:142`）。机器负载高时可能超时 → 又回落浏览器。想彻底消掉可以换成 piper 常驻 HTTP 服务（`python -m piper.http_server`），本次没做。
2. **默认只装英美模型**：中文/西语例句（`tts.voices.zh/es`）没有模型时仍回落浏览器；需要就 `setup-tts.sh --all`。
3. **Google 音色依赖网络**；断网时按规则退到本机音色（可能是 `Samantha`），那种情况下 item 7 的体感风险仍在，但已不是默认路径。
4. **生产未验证、未部署**：`docker-compose.prod.yml` 的 app 服务有 `RUN_TTS_SIDECAR: "true"`，Dockerfile 会装 piper 与模型；本次改动（sidecar 淡出、`dev.sh`、前端音色、`setup-tts.sh`）要下次构建/部署才到生产。
5. **没有 Java 改动**：运行中的 8010 是旧 jar，不需要重启；端点是靠「sidecar 起来后自动可用」生效的（本次就是这样验证的）。
6. `TtsAudioEndpoint.java:171` 的日志把异常压成 `TTS unavailable: null`（`ConnectException` 的 message 为空），排查时看不出是「连接被拒」。建议后续改成 `ex.toString()`，本次没动（避免为了日志重启 dev）。
