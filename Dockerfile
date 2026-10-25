# T160：多阶段构建。运行镜像内置中文字体（Noto CJK）与 ffprobe。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先只拷 POM 与 wrapper，利用依赖层缓存
COPY pom.xml mvnw mvnw.cmd ./
COPY .mvn .mvn
COPY teachloop-shared/pom.xml teachloop-shared/pom.xml
COPY teachloop-identity/pom.xml teachloop-identity/pom.xml
COPY teachloop-classroom/pom.xml teachloop-classroom/pom.xml
COPY teachloop-paper/pom.xml teachloop-paper/pom.xml
COPY teachloop-sitting/pom.xml teachloop-sitting/pom.xml
COPY teachloop-grading/pom.xml teachloop-grading/pom.xml
COPY teachloop-report/pom.xml teachloop-report/pom.xml
COPY teachloop-observability/pom.xml teachloop-observability/pom.xml
COPY teachloop-app/pom.xml teachloop-app/pom.xml
RUN ./mvnw -q -B -DskipTests dependency:go-offline || true

COPY . .
RUN ./mvnw -q -B -DskipTests package

FROM eclipse-temurin:21-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends fonts-noto-cjk ffmpeg curl python3 python3-pip \
 && rm -rf /var/lib/apt/lists/*
# T411：Piper 本地 TTS（模型随镜像；构建期需能访问模型源，可用 PIPER_MODEL_URL 覆盖）
# T427：美音 + 英音两个模型；复习卡的两个喇叭按 voice 选模型。
ARG PIPER_MODEL_URL=https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/medium/en_US-amy-medium.onnx
ARG PIPER_CONFIG_URL=https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/medium/en_US-amy-medium.onnx.json
ARG PIPER_UK_MODEL_URL=https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/alan/medium/en_GB-alan-medium.onnx
ARG PIPER_UK_CONFIG_URL=https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/alan/medium/en_GB-alan-medium.onnx.json
RUN pip install --no-cache-dir piper-tts \
 && mkdir -p /app/piper \
 && curl -fsSL "$PIPER_MODEL_URL" -o /app/piper/en_US-amy-medium.onnx \
 && curl -fsSL "$PIPER_CONFIG_URL" -o /app/piper/en_US-amy-medium.onnx.json \
 && curl -fsSL "$PIPER_UK_MODEL_URL" -o /app/piper/en_GB-alan-medium.onnx \
 && curl -fsSL "$PIPER_UK_CONFIG_URL" -o /app/piper/en_GB-alan-medium.onnx.json
WORKDIR /app
COPY --from=build /build/teachloop-app/target/teachloop-app-*.jar /app/app.jar
COPY docker/piper-sidecar.py /app/piper-sidecar.py
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh \
 && mkdir -p /app/logs /app/uploads && useradd --system --uid 10001 teachloop \
 && chown -R teachloop:teachloop /app
USER teachloop
EXPOSE 8010
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -fsS "http://127.0.0.1:8010/healthz?deep=1" || exit 1
ENTRYPOINT ["/app/entrypoint.sh"]
