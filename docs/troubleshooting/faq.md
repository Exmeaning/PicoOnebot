# 常见问题 (FAQ)

本文整理了在部署和使用 PicoOnebot 过程中常见的问题与解决建议。

---

## 容器启动与网络

### 1. 容器启动后无法立即访问 6099 端口（提示拒绝连接）？
**原因**：首次冷启动需要完整执行 Android 系统首次引导、APK 安装、授权弹窗自动模拟点击以及 QIMEI 初始化流程。在软件渲染（CPU 渲染）模式下通常耗时 **2–5 分钟**。
**排查**：
- 执行 `docker ps`，观察状态是否为 `(health: starting)`；
- 状态变为 `(healthy)` 时即表示 6099 端口已被成功监听；
- 若超过 10 分钟仍处于 `unhealthy` 状态，请参考 [诊断工具与排错](/troubleshooting/doctor) 执行诊断脚本。

### 2. `docker logs` 没有任何输出或输出极少？
**原因**：Android init 系统的底层输出默认写入内核日志环形缓冲区 `/dev/kmsg` 而非容器标准输出。
**排查**：
- 本镜像默认通过 entrypoint 将关键启动日志转发至 stdout；
- 若需要查看全量内核日志，启动时可传入环境变量 `-e PICO_LOG_KMSG=all`；
- 或直接在容器内读取 dmesg：
  ```bash
  docker exec pico-onebot /system/bin/dmesg | grep -iE 'binder|permission denied'
  ```

### 3. 反向 WebSocket 无法连接到机器人框架？
**原因**：最常见的原因是在容器内填写的地址为 `127.0.0.1`，此时容器试图连接容器自身的内部端口，而非宿主机或局域网上的框架服务端。
**解决**：
- 若框架在宿主机上运行，可将连接地址修改为宿主机的局域网 IP（例如 `192.168.x.x`）或 Docker 网桥 IP（通常为 `172.17.0.1`）；
- 若两者使用同一 `docker compose` 网络，可直接使用框架对应的服务名称（例如 `ws://nonebot:8080/onebot/v11/ws`）。

---

## 登录与风控

### 4. 扫码后手机 QQ 提示安全风险或禁止手表端登录？
**建议**：
- 建议机器人使用绑定了实名并具备一定活跃记录的正常 QQ 账号，避免使用新注册小号；
- 保持同一网络环境（手机与机器人所在机器在同一局域网）有助于降低风控判定概率。

### 5. 扫码成功后网页卡住或未刷新？
**处理**：
- 刷新 WebUI 网页；
- 若长时间未更新，可尝试在控制台点击「重新拉取状态」或重启容器一次。

---

## 内核与系统

### 6. 能否在普通的 Docker Desktop (Windows / macOS) 上直接部署？
**说明**：不能。Docker Desktop 底层依赖精简的虚拟机内核（WSL2 或 LinuxKit），默认未启用 Android 所需的 `CONFIG_ANDROID_BINDER_IPC` 和 `CONFIG_ANDROID_BINDERFS`。
- Windows 用户可参考 [WSL2 环境配置](/deploy/wsl2) 自定义编译内核；
- 或采用 [APK 直装方案](/deploy/apk) 在 Android 模拟器中运行。

### 7. 是否需要额外配置 `/dev/ashmem`？
**说明**：不需要。PicoOnebot 镜像基于 Android 13，Android 系统自 Android 10+ 起已逐步由 Linux 原生 `memfd` 替代了 ashmem 驱动。

---

## APK 安装

### 8. 安装 APK 报错 `INSTALL_FAILED_NO_MATCHING_ABIS` / `Failed to extract native libraries, res=-113`？
**原因**：这台设备不支持运行 32 位（`armeabi-v7a`）应用。PicoOnebot 以官方手表 QQ 为底包，手表 QQ 只有 32 位原生库且官方没有 64 位版本，本项目无法在 APK 层面修复。
常见于 Pixel 7 及之后机型、部分三星与海外版机型；搭载同款纯 64 位芯片的大部分国产旗舰因厂商内置了 32 位转译层，通常可以正常安装。
**排查**：
```bash
adb shell getprop ro.product.cpu.abilist
```
输出中不含 `armeabi-v7a` 即为此问题。更换安装器、重新签名均无效。
**解决**：改用其他运行环境，任选其一：
- 手机内运行光速虚拟机 / VMOS Pro 等自带 32 位转译的虚拟机，在虚拟机内安装 APK；
- 电脑模拟器（MuMu、雷电、BlueStacks 等）；
- [Docker 一体化容器](/deploy/docker) 或 Waydroid。

详细步骤见 [纯 64 位设备的备选方案](/deploy/apk#无法安装-纯-64-位设备的备选方案)。
