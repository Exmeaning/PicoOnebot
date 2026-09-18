# WSL2 环境配置与自编译内核

微软官方发布的 WSL2 预编译内核为了保证系统体积与精简性，默认未开启 Android Binder IPC 相关的内核选项。如果在原生 WSL2 中直接运行 PicoOnebot 容器，会导致系统服务无法初始化。

若希望在本地 Windows 开发机中使用 WSL2 运行 PicoOnebot，可通过自行编译开启 Binder 支持的内核来实现。

---

## 编译步骤

### 1. 准备构建依赖（在 WSL2 Ubuntu 中执行）

```bash
sudo apt update
sudo apt install -y build-essential flex bison libssl-dev libelf-dev bc git dwarves libncurses-dev
```

### 2. 克隆微软 WSL2 内核源码

```bash
git clone --depth=1 https://github.com/microsoft/WSL2-Linux-Kernel.git
cd WSL2-Linux-Kernel
```

### 3. 配置内核选项

使用当前的 WSL2 内核配置作为底包：
```bash
zcat /proc/config.gz > .config
```

通过配置工具或直接在 `.config` 中确认并追加开启以下选项：
```ini
CONFIG_ANDROID=y
CONFIG_ANDROID_BINDER_IPC=y
CONFIG_ANDROID_BINDERFS=y
CONFIG_ANDROID_BINDER_DEVICES="binder,hwbinder,vndbinder"
```

### 4. 编译内核

```bash
make -j$(nproc) bzImage
```

编译完成后，内核二进制镜像位于 `arch/x86/boot/bzImage`。

---

## 应用新内核

### 1. 将内核文件拷贝至 Windows 文件系统

```bash
# 例如拷贝到当前 Windows 用户的用户目录下
cp arch/x86/boot/bzImage /mnt/c/Users/<你的用户名>/wsl2-kernel-binder
```

### 2. 配置 `.wslconfig`

在 Windows 用户主目录（`C:\Users\<你的用户名>\.wslconfig`）中创建或编辑配置文件：

```ini
[wsl2]
kernel=C:\\Users\\<你的用户名>\\wsl2-kernel-binder
```

> [!NOTE] 路径格式
> Windows 路径中的反斜杠请使用双反斜杠 `\\`，或者使用正斜杠 `/`，例如 `C:/Users/<你的用户名>/wsl2-kernel-binder`。

### 3. 重启 WSL2

在 Windows PowerShell 中执行命令彻底关闭并重启 WSL：

```powershell
wsl --shutdown
```

重新打开 WSL2 终端后，检查 Binder 支持是否生效：

```bash
# 检查是否包含 binder 文件系统
grep -w binder /proc/filesystems
```

有输出即代表内核已成功支持 binderfs，随后即可按 [Docker 部署指引](/deploy/docker) 正常拉起 PicoOnebot 容器。
