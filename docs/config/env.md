# 环境变量与配置参数全集

在使用 Docker 或 Kubernetes 部署 PicoOnebot 时，可通过环境变量对容器运行时行为、日志输出及端口映射进行灵活配置。

---

## 容器环境变量

以下变量可在 `docker run -e KEY=VALUE` 或 `compose.yml` 的 `environment` 节点中指定：

| 变量名 | 默认值 | 可选值 | 说明 |
| --- | --- | --- | --- |
| `PICO_LOG_KMSG` | `1` | `1`, `all`, `0` | 控制是否将系统 `/dev/kmsg`（Android init 与内核输出）转发至容器 stdout。<br>• `1`：仅转发核心系统与服务日志（推荐）<br>• `all`：转发所有全量内核日志<br>• `0`：关闭转发，`docker logs` 仅输出应用日志 |
| `PICO_BINDER_STRICT` | `0` | `0`, `1` | 控制 Binder 预检失败时的处理行为。<br>• `0`：打印告警后仍继续启动，便于 `docker exec` 进入排查（推荐）<br>• `1`：检查失败时立即以退出码 78 退出容器 |

---

## Compose / 宿主环境变量 (.env)

当使用 `docker compose` 启动时，可通过项目目录下的 `.env` 文件调整宿主机端口映射与构建镜像：

| 变量名 | 默认值 | 说明 |
| --- | --- | --- |
| `PICO_IMAGE` | `ghcr.io/exmeaning/pico-onebot:latest` | 使用的 Docker 镜像源。本地构建可指定对应 tag |
| `PICO_WEBUI_PORT` | `6099` | 宿主机映射出的 WebUI 控制台访问端口 |
| `PICO_ONEBOT_PORT` | `3001` | 宿主机映射出的 OneBot 正向通信服务端口 |
| `PICO_ADB_PORT` | `5555` | 内部 ADB 调试端口（默认仅绑定到 `127.0.0.1`） |

---

## 容器系统内部目录参考

| 容器内路径 | 用途说明 |
| --- | --- |
| `/data` | Android 系统数据持久化目录（应挂载至 Docker Volume 或宿主机目录） |
| `/data/pico/pico-boot.log` | 引导守护脚本日志文件 |
| `/opt/pico-onebot/PicoOnebot.apk` | 镜像内置的 APK 安装包文件 |
| `/system/bin/pico-doctor.sh` | 容器内置系统诊断脚本 |
| `/system/bin/pico-health.sh` | Docker HEALTHCHECK 探针脚本 |
