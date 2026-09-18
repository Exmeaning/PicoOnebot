# 诊断工具与日志排查

当遇到容器无法启动、服务端口未监听、扫码界面空白或消息未正常收发时，可使用容器内置的一键诊断工具和日志检索命令进行快速排查。

---

## 1. 一键诊断脚本 (`pico-doctor.sh`)

容器内部集成了自动化诊断工具，能够全面检查 Binder 设备、系统服务、转译层库、APK 安装状态及端口监听。

### 执行方法
在宿主机终端中执行：

```bash
docker exec pico-onebot /system/bin/sh /system/bin/pico-doctor.sh
```

### 输出检查项对照

| 检查大项 | 期望输出 | 异常提示与处理 |
| --- | --- | --- |
| **1. Binder 设备与权限** | `Binder 节点状态正常` | 若提示 `权限不是 0666`，需在宿主机执行 `chmod 666` 并配置 udev 规则，或使用 binderfs 模式。 |
| **2. Android 启动状态** | `sys.boot_completed = 1`<br>核心系统服务显示 `运行中` | 若核心服务显示 `未运行`，通常说明 Binder 驱动通信受阻，导致 init 崩溃循环。 |
| **3. ARM 转译层** | arm64 环境或 amd64 检测到 `libndk_translation.so` | 若 amd64 平台缺失该库，arm 架构应用将无法执行。 |
| **4. 应用状态** | 显示版本号，进程显示 `运行中` | 若显示 `未安装`，请检查镜像构建是否成功拷贝了 APK。 |
| **5. 端口监听** | `6099 端口处于监听状态` | 若未监听，检查应用是否被守护脚本拉起。 |

> [!TIP] 提交 Issue 建议
> 若遇到无法自行解决的部署异常，在 GitHub 提交 Issue 时，请将 `pico-doctor.sh` 的完整终端输出附在 Issue 内容中。

---

## 2. 常用日志排查命令

### 查看 PicoOnebot 引导服务日志
记录了自容器开机以来的 APK 安装、权限授予、弹窗模拟点击与就绪检测全过程：
```bash
docker exec pico-onebot /system/bin/cat /data/pico/pico-boot.log
```

### 查看应用层 Logcat
查看手表 QQ 与 Hook 插件的核心运行日志：
```bash
docker exec pico-onebot /system/bin/logcat -d -s PicoOB:V AndroidRuntime:E
```

### 检索内核 Binder 错误日志
```bash
docker exec pico-onebot /system/bin/dmesg | grep -iE 'binder|permission denied'
```

### 进入容器内部交互 Shell
```bash
docker exec -it pico-onebot /system/bin/sh
```
进入后可直接运行 Android 调试命令（如 `getprop`、`pm list packages`、`am`、`ps -ef` 等）。
