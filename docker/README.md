# PicoOnebot Docker 部署指南

官方 `ghcr.io/exmeaning/pico-onebot:latest` 镜像为**一体化（All-in-One）镜像**，已内置 Redroid (Android 13)、Google `libndk` (ARM 转译层)、PicoOnebot APK 与自动启动保活服务。

无需管理多个容器，单镜像拉起即可使用。

---

## 前置条件

1. **宿主机内核支持 Binder**：
   ```bash
   modprobe binder_linux
   test -e /dev/binder && echo "Binder 就绪"
   ```
   *持久化加载（防止重启失效）：*
   ```bash
   echo "binder_linux" | sudo tee /etc/modules-load.d/binder.conf
   ```

2. **支持特权容器**：Android 容器（Redroid）需在特权模式（`--privileged`）下运行以管理 Android 设备节点。

---

## 快速启动（推荐：单命令一体化运行）

```bash
docker run -d \
  --name pico-onebot \
  --privileged \
  -v /dev/binder:/dev/binder \
  -v /var/lib/pico-onebot-data:/data \
  -p 6099:6099 \
  -p 3001:3001 \
  --restart unless-stopped \
  ghcr.io/exmeaning/pico-onebot:latest
```

### 访问控制台
- 打开浏览器访问：`http://<服务器IP>:6099`
- 首次进入初始密码为：`picopico`（进入后需重置密码）；
- 登录后在页面即可查看扫码二维码，使用手机 QQ 扫码完成登录；
- 在「网络配置」添加 WebSocket / HTTP 即可对接机器人框架。

---

## Kubernetes / K3s 部署

如果使用的是 K3s 或 Kubernetes 集群，可直接应用本仓库提供的清单文件：

```bash
kubectl apply -f docker/pico-k8s.yaml
```

- 默认以 NodePort 暴露端口：
  - WebUI 控制台：`http://<节点IP>:30099`
  - OneBot v11 端口：`http://<节点IP>:30001`
- 数据持久化保存在宿主机的 `/var/lib/pico-onebot-data`。

---

## 常用运维命令

```bash
# 查看容器内部开机与手表 QQ 运行日志
docker logs -f pico-onebot

# 进入 Android 调试 Shell
docker exec -it pico-onebot /system/bin/sh

# 连接内部 ADB（如需截屏或调试）
adb connect 127.0.0.1:5555
adb -s 127.0.0.1:5555 shell
```
