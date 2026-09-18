# 部署方案总览

PicoOnebot 支持多种部署形式，可根据硬件条件与运维需求选择适合的方案。

---

## 方案选型对照

| 部署形态 | 适用环境 | 优点 | 缺点 / 前提 | 建议度 |
| --- | --- | --- | --- | --- |
| **APK 直装** | 闲置安卓手机、平板、电脑模拟器 (MuMu/雷电/AVD) | 开箱即用，无需 Linux 与内核配置经验 | 需要运行 Android 设备或常驻桌面模拟器 | 极简上手 |
| **Docker 容器** | Linux 云服务器 (KVM)、物理主机、软路由 | 单命令运行，纯无头后台运行，便于自动化管理 | 依赖宿主机内核提供 Binder 支持 | 推荐（服务器首选） |
| **K8s / K3s** | 云原生 Kubernetes / K3s 集群 | 自动健康检查、容器漂移恢复、声明式管理 | 节点需要特权模式与 Binder 支持 | 云原生生产环境 |
| **WSL2** | Windows 开发机通过 WSL2 运行 Docker | 在本地 Windows 环境中进行开发与调试 | 默认 WSL2 内核未开启 Binder，需自编译内核 | 适合开发调试 |

---

## 快速导航

- [Android 设备 / 模拟器 (APK 安装)](/deploy/apk)：在实体手机或桌面模拟器上直接运行 APK。
- [Docker 一体化容器部署](/deploy/docker)：使用官方预构建 All-in-One 镜像进行单容器或 Compose 部署。
- [Kubernetes / K3s 集群部署](/deploy/k8s)：基于标准 Deployment 清单部署到 K8s 节点。
- [Linux 宿主机 Binder 配置指南](/deploy/binder)：深入解析 Binder 内核要求、权限调整与常见 Linux 发行版内核配置。
- [WSL2 环境配置与自编译内核](/deploy/wsl2)：Windows 平台通过自定义编译 WSL2 内核支持 PicoOnebot 容器。
