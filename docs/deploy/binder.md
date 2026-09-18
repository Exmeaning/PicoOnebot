# Linux 宿主机 Binder 配置指南

PicoOnebot 容器运行基于 Redroid 的 Android 13 环境，底层依赖宿主机 Linux 内核提供的 **Binder IPC** 支持。
如果宿主机内核未启用 Binder 或设备节点权限配置不当，Android 核心系统服务将无法启动。本文档系统介绍 Binder 机制、环境配置与常见问题排查。

---

## 环境自检

在宿主机终端执行自动化检测脚本：

```bash
curl -fsSL https://raw.githubusercontent.com/exmeaning/PicoOnebot/main/docker/scripts/pico-host-check.sh | bash
# 或克隆仓库后执行：
bash docker/scripts/pico-host-check.sh
```

检测结果判定：

| 检测状态 | 说明 | 建议操作 |
| --- | --- | --- |
| **binderfs 可用** | 内核开启 `CONFIG_ANDROID_BINDERFS` | 直接启动容器，无需映射宿主机设备节点 |
| **设备节点可用** | 存在 `/dev/binder` 等节点 | 配置节点权限为 0666 并通过 `--device` 传入 |
| **内核不支持** | 未包含 binderfs 与 binder 驱动 | 需更换支持 Binder 的内核，或改用 APK 运行方式 |

---

## 运行机制

Binder 是 Android 系统的核心进程间通信机制，由内核驱动（`/dev/binder`、`/dev/hwbinder`、`/dev/vndbinder`）提供。Android 的系统服务（如 `servicemanager`、`surfaceflinger`、`system_server` 等）均依赖 Binder 通信。

由于 Redroid 容器与宿主机共享 Linux 内核，Binder 必须由宿主机内核提供。

---

## 部署方案

### 方案 A：binderfs（推荐）

内核开启 `CONFIG_ANDROID_BINDERFS=y` 时，Binder 支持以文件系统形式由容器独立挂载。容器内 Android init 会自动完成挂载与权限设置：

```text
mount binder binder /dev/binderfs stats=global
symlink /dev/binderfs/binder /dev/binder
chmod 0666 /dev/binderfs/binder
```

**运行配置**：容器仅需启用 `--privileged`，**不要**映射宿主机的 binder 设备：

```bash
docker run -d --name pico-onebot --privileged \
  --shm-size 1g \
  -p 6099:6099 -p 3001:3001 \
  -v pico-data:/data \
  --restart unless-stopped \
  ghcr.io/exmeaning/pico-onebot:latest
```

### 方案 B：binder_linux 模块与设备节点

若内核未启用 binderfs，可通过 `binder_linux` 驱动提供字符设备节点：

```bash
sudo modprobe binder_linux devices=binder,hwbinder,vndbinder
ls -l /dev/binder
```

宿主机 udev 默认创建的设备权限通常为 `0600`（仅 root 读写），需调整权限后传入容器。

---

## 设备节点权限问题 (Permission denied)

### 现象与原因

- 容器处于 Up 状态，但 WebUI（6099 端口）拒绝连接；
- 容器内 `dmesg` 输出如下错误：
  ```text
  Binder driver '/dev/binder' could not be opened.
  Opening '/dev/binder' failed: Permission denied
  ```
- Android 系统服务（`servicemanager`、`surfaceflinger` 等）以 `system` 等非 root UID 运行。宿主机节点若为 `0600`，非 root 进程无权访问 Binder，导致核心服务崩溃循环，WebUI 无法拉起。

### 解决方法

#### 方案 1（推荐）：改用 binderfs
若宿主机内核支持 binderfs，移除 `--device` 或 `-v` 中的 binder 设备映射，由容器自动挂载。

#### 方案 2：修改设备节点权限
放开设备权限为 `0666`，并配置 udev 规则持久化：

```bash
sudo chmod 666 /dev/binder /dev/hwbinder /dev/vndbinder

# 配置 udev 规则持久化
sudo tee /etc/udev/rules.d/99-binder.rules >/dev/null <<'RULES'
KERNEL=="binder",    MODE="0666"
KERNEL=="hwbinder",  MODE="0666"
KERNEL=="vndbinder", MODE="0666"
RULES
sudo udevadm control --reload && sudo udevadm trigger
```

同时持久化内核模块加载：

```bash
echo binder_linux | sudo tee /etc/modules-load.d/binder.conf
echo "options binder_linux devices=binder,hwbinder,vndbinder" \
  | sudo tee /etc/modprobe.d/binder.conf
```

#### 设备挂载参数说明 (`--device` 与 `-v`)

挂载字符设备应使用 `--device`：
```bash
# 正确方式
--device /dev/binder --device /dev/hwbinder --device /dev/vndbinder

# 避免使用 -v：若宿主机设备节点不存在，Docker 会自动将其创建为普通目录挂载，导致 Android 启动失败
-v /dev/binder:/dev/binder
```

---

## 内核兼容性参考

| 运行环境 | Binder 支持情况 | 说明 |
| --- | --- | --- |
| Ubuntu 22.04+、Debian 12+ (KVM / 物理机) | 支持 | 标准内核通常内置 `CONFIG_ANDROID_BINDERFS=y` |
| Arch / Fedora / openSUSE | 支持 | 现代内核通常内置支持 |
| CentOS 7 / 早期 Linux 内核 (< 4.19) | 需自行配置 | 无 binderfs，需通过 DKMS 编译模块使用设备节点 |
| 原生 WSL2（微软官方内核） | 不支持 | 默认内核未包含 Binder 相关配置，需自定义编译内核 |
| Docker Desktop (Windows / macOS) | 不支持 | 虚拟机底层内核未提供 Binder 支持 |
| OpenVZ / LXC 虚拟化 VPS | 不支持 | 共享宿主内核且通常禁止加载内核模块 |
| 群晖 DSM / 威联通 QTS | 不支持 | 系统内核未开启 Binder 支持 |
| 云厂商标准实例 (KVM 架构) | 通常支持 | 建议运行自检脚本确认 |

检查内核配置项：

```bash
zcat /proc/config.gz 2>/dev/null | grep -E 'ANDROID_BINDER|ANDROID_BINDERFS'
# 或
grep -E 'ANDROID_BINDER|ANDROID_BINDERFS' "/boot/config-$(uname -r)"
```
