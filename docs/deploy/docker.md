# Docker 一体化容器部署

官方 Docker 镜像 `ghcr.io/exmeaning/pico-onebot:latest` 为一体化（All-in-One）镜像，内置 Redroid (Android 13)、Google `libndk`（ARM 转译层）、PicoOnebot APK 及守护服务。无需额外管理多个容器，拉起即可使用。

---

## 部署前提

1. **宿主机内核支持 Binder**：
   - 绝大多数 Ubuntu 22.04+、Debian 12+ 及现代 Linux 内核均已内置 `binderfs` 支持；
   - 部署前请先在宿主机执行环境检测脚本（详见 [Binder 内核指南](/deploy/binder)）：
     ```bash
     bash docker/scripts/pico-host-check.sh
     ```
2. **支持特权容器**：必须使用 `--privileged` 参数运行，以允许容器内部挂载 binder 文件系统。

> [!WARNING] 不受支持的环境
> Docker Desktop (Windows / macOS)、原生 WSL2（微软未开 Binder 选项的内核）、OpenVZ / LXC 架构的 VPS 以及群晖/威联通等 NAS 系统默认无法直接运行本镜像。若需在 Windows 开发机运行，请参考 [WSL2 环境配置](/deploy/wsl2)。

---

## 快速启动

### 方案 A：binderfs 模式（推荐，绝大多数较新内核）

当内核支持 `CONFIG_ANDROID_BINDERFS=y` 时，无需向容器映射宿主机任何 binder 设备，由容器内的 Android init 自动挂载：

```bash
docker run -d \
  --name pico-onebot \
  --privileged \
  --shm-size 1g \
  -p 6099:6099 \
  -p 3001:3001 \
  -v pico-data:/data \
  --restart unless-stopped \
  ghcr.io/exmeaning/pico-onebot:latest
```

### 方案 B：字符设备节点映射模式

若内核未编译 binderfs，但宿主机已通过 `binder_linux` 驱动生成了字符设备节点（如 `/dev/binder`）：

1. **调整宿主机设备节点权限**（避免 Android 非 root 服务因权限不足崩溃）：
   ```bash
   sudo chmod 666 /dev/binder /dev/hwbinder /dev/vndbinder

   # 持久化 udev 规则以防重启还原
   sudo tee /etc/udev/rules.d/99-binder.rules >/dev/null <<'RULES'
   KERNEL=="binder",    MODE="0666"
   KERNEL=="hwbinder",  MODE="0666"
   KERNEL=="vndbinder", MODE="0666"
   RULES
   sudo udevadm control --reload && sudo udevadm trigger
   ```

2. **启动容器**（使用 `--device` 映射字符设备）：
   ```bash
   docker run -d \
     --name pico-onebot \
     --privileged \
     --shm-size 1g \
     --device /dev/binder --device /dev/hwbinder --device /dev/vndbinder \
     -p 6099:6099 \
     -p 3001:3001 \
     -v pico-data:/data \
     --restart unless-stopped \
     ghcr.io/exmeaning/pico-onebot:latest
   ```

---

## 使用 Docker Compose 部署

通过 Docker Compose 能够更便捷地管理端口映射、环境变量与数据卷。

1. **准备配置文件**：
   在项目目录中，拷贝环境变量示例：
   ```bash
   cp docker/.env.example docker/.env
   ```

2. **配置文件说明 (`docker/compose.yml`)**：
   ```yaml
   services:
     pico-onebot:
       image: ${PICO_IMAGE:-ghcr.io/exmeaning/pico-onebot:latest}
       build:
         context: ..
         dockerfile: docker/Dockerfile
       privileged: true
       restart: unless-stopped
       shm_size: "1gb"
       stop_grace_period: 30s
       ports:
         - "${PICO_WEBUI_PORT:-6099}:6099"
         - "${PICO_ONEBOT_PORT:-3001}:3001"
         - "127.0.0.1:${PICO_ADB_PORT:-5555}:5555"
       volumes:
         - android-data:/data
       environment:
         PICO_LOG_KMSG: "${PICO_LOG_KMSG:-1}"
         PICO_BINDER_STRICT: "${PICO_BINDER_STRICT:-0}"
       # 若走设备节点路线，取消以下注释：
       # devices:
       #   - /dev/binder:/dev/binder
       #   - /dev/hwbinder:/dev/hwbinder
       #   - /dev/vndbinder:/dev/vndbinder

   volumes:
     android-data:
   ```

3. **启动容器**：
   ```bash
   docker compose -f docker/compose.yml up -d
   ```

---

## 启动耗时与就绪状态

首次启动时，容器需要执行 Android 完整开机初始化、APK 静默安装以及授权点击流程，通常需要 **2–5 分钟**。
在初始化完成前，浏览器访问 6099 端口显示「拒绝连接」属于正常现象。

可通过查看容器健康检查状态确认是否就绪：
```bash
docker ps --format '{{.Names}}\t{{.Status}}'
# 输出: Up 3 minutes (healthy) 即表示已完全就绪
```

---

## 日志观测与诊断

```bash
# 实时查看容器标准输出（包含 entrypoint 与核心内核日志）
docker logs -f pico-onebot

# 运行容器内置诊断脚本
docker exec pico-onebot /system/bin/sh /system/bin/pico-doctor.sh

# 查看 PicoOnebot 自身引导守护日志
docker exec pico-onebot /system/bin/cat /data/pico/pico-boot.log
```
