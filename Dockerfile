# syntax=docker/dockerfile:1.7

# Playwright Chromium 已预构建到独立镜像；版本必须与 pom.xml 中的 Java Playwright 依赖一致。
ARG PLAYWRIGHT_BASE_IMAGE=ghcr.io/daki-l/xianyu2-playwright:v1.61.0
FROM ${PLAYWRIGHT_BASE_IMAGE} AS playwright-browser

# ===== 多阶段构建 =====

# 阶段1: 构建前端
FROM node:20-alpine AS frontend-build

WORKDIR /app/vue-code

# 设置 npm 镜像源
RUN npm config set registry https://registry.npmmirror.com

# 先复制依赖文件，利用缓存
COPY vue-code/package.json vue-code/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci

# 复制前端源码并构建
COPY vue-code/ ./
RUN npm run type-check && npm run build:spring

# 阶段2: 构建后端 JAR
FROM eclipse-temurin:21-jdk-jammy AS backend-build

WORKDIR /app

# 先复制 Maven 配置和 pom.xml，利用缓存
COPY .mvn/ .mvn/
COPY mvnw mvnw.cmd pom.xml ./
RUN chmod +x mvnw

# 单独解析依赖，业务源码变更时复用 Maven 缓存。
RUN --mount=type=cache,target=/root/.m2/repository ./mvnw -B -DskipTests dependency:go-offline

# 复制后端源码
COPY src/ src/
# 复制前端构建产物到 static 目录
COPY --from=frontend-build /app/vue-code/../src/main/resources/static src/main/resources/static/
# 行政区划数据由Maven作为后端资源打包。
COPY vue-code/src/data/ vue-code/src/data/

# 测试由 CI 的独立 test Job 执行；镜像阶段只负责编译和打包，不再重复编译测试代码。
RUN --mount=type=cache,target=/root/.m2/repository ./mvnw -B -Dmaven.test.skip=true package

# 阶段3: 运行时镜像
FROM eclipse-temurin:21-jre-jammy

LABEL org.opencontainers.image.title="XianYu2"
LABEL org.opencontainers.image.description="多租户闲鱼虚拟商品运营平台"
LABEL org.opencontainers.image.version="2.0.7"
LABEL org.opencontainers.image.licenses="PolyForm-Noncommercial-1.0.0"

WORKDIR /app

# Chromium 仅在刷新凭证时按需启动，运行库不会产生常驻进程
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates fonts-liberation fonts-noto-cjk libasound2 libatk-bridge2.0-0 libatk1.0-0 \
        libatspi2.0-0 libcairo2 libcups2 libdbus-1-3 libdrm2 libfontconfig1 \
        libgbm1 libglib2.0-0 libgtk-3-0 libnspr4 libnss3 libpango-1.0-0 \
        libx11-6 libxcb1 libxcomposite1 libxdamage1 libxext6 libxfixes3 \
        libxkbcommon0 libxrandr2 libxshmfence1 wget \
    && rm -rf /var/lib/apt/lists/*

# 创建低权限运行用户和数据目录
RUN groupadd --system xianyu2 && useradd --system --gid xianyu2 --home-dir /app xianyu2 \
    && mkdir -p /app/data /app/logs \
    && chown -R xianyu2:xianyu2 /app

# 从构建阶段复制 JAR
COPY --from=backend-build --chown=xianyu2:xianyu2 /app/target/xianyu2-2.0.7.jar app.jar
COPY --from=playwright-browser --chown=xianyu2:xianyu2 /ms-playwright /app/ms-playwright

# 暴露端口
EXPOSE 12400

# 环境变量
ENV JAVA_OPTS="-XX:MaxRAMPercentage=65 -XX:InitialRAMPercentage=20 -XX:+ExitOnOutOfMemoryError"
ENV SERVER_PORT=12400
ENV PLAYWRIGHT_BROWSERS_PATH=/app/ms-playwright
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

USER xianyu2

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:12400/actuator/health || exit 1

# 启动命令
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -Dserver.port=${SERVER_PORT} -jar app.jar"]
