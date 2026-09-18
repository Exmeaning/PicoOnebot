# PicoOnebot Docker 部署指南

官方 `ghcr.io/exmeaning/pico-onebot:latest` 为一体化（All-in-One）镜像，内置 Redroid (Android 13)、Google `libndk`（ARM 转译层）、PicoOnebot APK 及守护服务。

首次启动不依赖屏幕坐标：容器通过包管理器授予通知权限，PicoOnebot 在 QQ 隐私页创建后点击真实的 `agree` 控件，并在欢迎页创建后点击真实的 `login` 控件。隐私授权由 QQ 原生监听器落盘，随后仅重启一次 QQ 以重新初始化 QIMEI。

> **环境要求说明**
> 
> 镜像基于 Redroid 运行 Android 环境，依赖宿主机内核提供 **Binder IPC** 支持。
> 无法在未包含 Binder 内核驱动的环境中运行（如 Docker Desktop for Windows / macOS、原生 WSL2、OpenVZ / LXC 架构的 VPS 等）。
>
> 部署前可在宿主机运行检测脚本验证内核支持情况：
> ```bash
> bash docker/scripts/pico-host-check.sh
> ```
> 详细说明参见 [官方文档站](https://exmeaning.github.io/PicoOnebot/deploy/docker) 或 [Binder 配置指南](../docs/deploy/binder.md)。

---

## 前置要求

1. **宿主机内核 Binder 支持**（满足其一即可）：
   - **binderfs（推荐）**：内核启用 `CONFIG_ANDROID_BINDERFS=y`。容器内的 Android init 会自动挂载 binderfs，无需映射宿主机设备节点；
   - **binder_linux 模块**：宿主机存在 `/dev/binder` 等设备节点，需赋予 `0666` 权限并通过 `--device` 传入容器。

   ```bash
   # 检查 binderfs 是否可用（有输出即支持）
   grep -w binder /proc/filesystems
   # 检查设备节点是否存在
   ls -l /dev/binder 2>/dev/null
   ```

2. **特权模式支持**：Android 容器需以 `--privileged` 运行以挂载文件系统与管理设备节点。

---

## 快速启动

### 方案 A：binderfs 模式（推荐，绝大多数较新内核）

无需映射宿主机 binder 设备，由容器内 Android init 自动挂载：

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

### 方案 B：设备节点映射模式

若内核未启用 binderfs，可使用 `binder_linux` 驱动节点。先调整宿主机节点权限（避免因 0600 权限导致系统服务拒绝访问）：

```bash
sudo chmod 666 /dev/binder /dev/hwbinder /dev/vndbinder

# 配置 udev 规则持久化权限
sudo tee /etc/udev/rules.d/99-binder.rules >/dev/null <<'RULES'
KERNEL=="binder",    MODE="0666"
KERNEL=="hwbinder",  MODE="0666"
KERNEL=="vndbinder", MODE="0666"
RULES
sudo udevadm control --reload && sudo udevadm trigger
```

使用 `--device` 挂载字符设备并启动：

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

### Docker Compose

```bash
cp docker/.env.example docker/.env      # 可选：修改端口等环境变量
docker compose -f docker/compose.yml up -d
```

`compose.yml` 默认采用 binderfs 模式；若使用设备节点模式，取消文件中对应 `devices` 注释即可。

---

## 状态检查与访问

### 启动耗时说明

首次启动需完成 Android 系统初始化、APK 安装及首开引导流程，耗时约 2–5 分钟。
镜像内置了健康检查探针，可通过 `docker ps` 查看容器状态：

```bash
docker ps --format '{{.Names}}\t{{.Status}}'
# Up 2 minutes (health: starting)   → 系统正在初始化
# Up 4 minutes (healthy)            → 系统与 WebUI 端口已就绪
# Up 10 minutes (unhealthy)         → 初始化异常，需排查日志
```

### 访问控制台

- 浏览器访问：`http://<服务器IP>:6099`；
- 初始密码：`picopico`（首次登录需修改密码）；
- 登录后在页面查看登录二维码，使用手机 QQ 扫码完成登录；
- 在「网络配置」中添加 WebSocket / HTTP 地址即可对接 OneBot 机器人框架。

---

## Kubernetes / K3s 部署

```bash
kubectl apply -f docker/pico-k8s.yaml
```

- 默认以 NodePort 暴露端口：WebUI `http://<节点IP>:30099`，OneBot v11 `<节点IP>:30001`；
- 数据持久化存储于节点的 `/var/lib/pico-onebot-data`；
- 清单默认使用 binderfs 模式，设备节点模式的挂载项已在文件中提供注释。

---

## 日志与诊断

### 日志输出机制

Android init 默认将系统日志写入 `/dev/kmsg`。本镜像配置了日志转发支持：

1. 容器启动时先执行环境与 Binder 检查，将检测结果输出至 stdout；
2. 默认将 `/dev/kmsg` 中核心系统与服务日志转发至 stdout（可通过环境变量 `PICO_LOG_KMSG` 配置）；
3. `pico-boot` 守护日志同步写入 `/data/pico/pico-boot.log` 与 stdout。

若执行 `docker logs` 没有任何输出，通常表示容器启动阶段即异常退出，可通过 `docker inspect` 确认容器退出码。

### 一键诊断

容器内置了系统与环境诊断脚本：

```bash
docker exec pico-onebot /system/bin/sh /system/bin/pico-doctor.sh
```

输出内容包含 Binder 权限、Android 服务运行状态、ARM 转译库、APK 安装状态、端口监听以及近期日志。提交 Issue 时建议附带该输出信息。

### 常用命令

```bash
# 查看容器运行日志
docker logs -f pico-onebot

# 查看内核与系统日志
docker exec pico-onebot /system/bin/dmesg | grep -iE 'binder|permission denied'

# 查看 PicoOnebot 引导日志
docker exec pico-onebot /system/bin/cat /data/pico/pico-boot.log

# 查看应用 logcat 日志
docker exec pico-onebot /system/bin/logcat -d -s PicoOB:V

# 进入 Android Shell
docker exec -it pico-onebot /system/bin/sh

# 连接内部 ADB（调试用）
adb connect 127.0.0.1:5555
adb -s 127.0.0.1:5555 shell
```

---

## 环境变量配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PICO_LOG_KMSG` | `1` | 将 `/dev/kmsg` 转发至容器 stdout。`1`=仅转发关键服务日志，`all`=全量转发，`0`=关闭转发 |
| `PICO_BINDER_STRICT` | `0` | `1`=Binder 校验失败时直接退出容器（退出码 78）；`0`=仅输出告警并继续运行 |

---

## 常见排错

| 现象 | 可能原因 | 解决建议 |
| --- | --- | --- |
| 容器启动后 6099 无法连接且无日志 | 宿主机内核缺少 Binder 或权限不足 | 查看 entrypoint 启动日志，参考 [docs/BINDER.md](../docs/BINDER.md) |
| `dmesg` 出现 `Opening '/dev/binder' failed: Permission denied` | 宿主机设备节点权限为 0600 | 将设备权限调整为 `0666` 并配置 udev 规则，或使用 binderfs 模式 |
| 容器持续处于 unhealthy 状态 | Android 系统初始化未完成或异常 | 运行 `pico-doctor.sh` 查看服务运行状态与日志 |
| `modprobe binder_linux` 报错 `Module not found` | 当前内核未包含 binder 模块 | 更换支持 Binder 的内核或使用标准物理机/云服务器内核 |
| APK 未安装 | 镜像中缺少 APK 文件 | 使用官方发布镜像，或将 APK 放置于 `docker/PicoOnebot.apk` 后重新构建 |
| 扫码界面空白 | amd64 平台缺少 ARM 转译支持 | 运行 `pico-doctor.sh` 确认 `libndk_translation.so` 是否正常加载 |

## WebUI 重启 QQ

使用包含新版守护脚本的 PicoOnebot 容器时，控制台侧栏会显示「重启 QQ」。此操作只停止并重新启动 QQ，不重启 Android 或容器、不清除应用数据。重启后需重新登录 WebUI。

接口需要控制台鉴权，并校验容器标记和守护服务心跳；非容器、旧镜像或守护服务停止时不显示按钮，直接调用接口也会被拒绝。请求有 30 秒冷却期，容器脚本只执行固定的 QQ 停止/启动命令。

首次启用需要同时更新 APK 和容器镜像，然后保留 `/data` 数据卷重建容器；以后通过按钮即可单独重启 QQ。仅安装新 APK 不会安装容器守护脚本。

## 容器 DNS

入口脚本会把容器 `/etc/resolv.conf` 中的 DNS 服务器传给 Redroid，并合并容器 hosts 到 Android hosts；已有 QIMEI 映射保留。显式传入 `androidboot.redroid_net_dns*` 参数时尊重用户设置。

- 容器服务名：两个容器必须加入同一个用户自定义 Docker 网络，使用服务名及容器内部端口。
- 宿主机服务：Compose 已添加 `host.docker.internal:host-gateway`；使用 `docker run` 时自行添加 `--add-host=host.docker.internal:host-gateway`。宿主机服务需要监听容器可访问的网卡地址。
- `127.0.0.1` 指向 Pico 容器自身，不是宿主机或另一个容器。
- 网络修复需要重新构建镜像并重建容器（保留 `/data` 数据卷）；只更新 APK 不会更新入口脚本。
