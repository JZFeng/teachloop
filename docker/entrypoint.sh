#!/bin/sh
# T411：同镜像内可选启动 Piper sidecar（app 容器），worker 容器不启动。
if [ "${RUN_TTS_SIDECAR:-false}" = "true" ]; then
  python3 /app/piper-sidecar.py &
fi
exec java -XX:MaxRAMPercentage=75 -jar /app/app.jar
