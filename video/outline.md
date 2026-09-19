# Video Outline

> **主题**：待定（在 Checkpoint Plan 选择）
> **总时长**：约 3 分 44 秒（口播约 887 字 ÷ 4 字/秒）
> **章节数**：6 章 / 32 步

---

## 1. watch-to-onebot — 从手表 QQ 到协议端（5 steps · ~35s）

**信息池**（chapter agent 按需挂角标 / 副标 / pull-quote / mono cue）：
- 定位：基于官方 Watch QQ / QQ Lite 的 Android 原生 OneBot v11 协议端 —— 来源 article §1
- 接入机制：字节码注入 + Native Hook，通信服务运行在手表 QQ 进程内 —— 来源 article §1
- 运行条件：免 Root、免 Xposed —— 来源 article §1
- 工程元数据：主体语言 Kotlin，GPL-3.0，当前最新发布版 v0.13 —— 来源 article §1

**开发计划**：

- step 1 (~7s) — Linux 服务器与沉重桌面环境的反差画面，中央为“QQ 机器人怎么跑？”
- step 2 (~8s) — PicoOnebot 名称与“换一条路”主标语，配现有项目 Logo
- step 3 (~6s) — Watch QQ、PicoOnebot、OneBot v11 三层关系图
- step 4 (~7s) — 手表 QQ 进程内部的数据包与标准 OneBot 事件对照
- step 5 (~7s) — Kotlin、免 Root、免 Xposed 三个工程事实与注入链路总览

口播节选：
> 想在 Linux 上跑 QQ 机器人？又不想养一套桌面环境？PicoOnebot 换了条路。

---

## 2. light-protocol — 轻量取舍与生态连接（6 steps · ~41s）

**信息池**（chapter agent 按需挂角标 / 副标 / pull-quote / mono cue）：
- 资源对比：桌面 NT QQ Hook 方案约 500MB–1.5GB —— 来源 article §2
- 资源对比：手表 QQ 核心进程通常约 100MB–250MB —— 来源 article §2
- 能力取舍：裁掉富媒体、空间和小程序能力，以较低资源占用换取轻量部署 —— 来源 article §2
- 通信矩阵：正向 WebSocket、反向 WebSocket、HTTP API、HTTP POST 事件上报 —— 来源 article §3
- 兼容框架：NoneBot2、Koishi、AstrBot —— 来源 article §3

**开发计划**：

- step 1 (~6s) — “为什么是手表版？”大字提问与桌面端资源刻度
- step 2 (~7s) — 500MB 起步与 1.5GB 高位的占用对比
- step 3 (~7s) — 100MB–250MB 手表 QQ 核心进程区间
- step 4 (~7s) — “能力更少 / 部署更轻”双向取舍天平
- step 5 (~7s) — 四种 OneBot v11 通信通道矩阵
- step 6 (~7s) — NoneBot2、Koishi、AstrBot 围绕 OneBot v11 的兼容关系

口播节选：
> 手表 QQ 删掉了很多重功能。能力少了，但部署更轻。

---

## 3. messages-webui — 消息能力与 WebUI（5 steps · ~37s）

**信息池**（chapter agent 按需挂角标 / 副标 / pull-quote / mono cue）：
- 消息范围：私聊与群聊；文本、图片、语音、视频、文件收发 —— 来源 article §3
- 转发与查询：合并转发可读取解析，支持基础好友、群、成员、陌生人信息查询 —— 来源 article §3
- 控制台入口：内置 WebUI 默认监听 6099 端口 —— 来源 article §3
- 管理能力：扫码登录、连接与 Access Token 配置、实时日志、状态监控、文本配置编辑 —— 来源 article §3
- 配置体验：网络配置保存后热重载，无需重启 QQ —— 来源 article §3

**开发计划**：

- step 1 (~7s) — 私聊与群聊两条消息通道，画面聚焦文本、图片和语音
- step 2 (~7s) — 视频、文件、合并转发与基础信息查询能力板
- step 3 (~7s) — WebUI 浏览器界面占位与 6099 端口 hero 数字
- step 4 (~8s) — 二维码、Token、实时日志、运行状态四项管理视图
- step 5 (~8s) — 配置保存前后连接状态对照，强调“热重载 / 不重启 QQ”

口播节选：
> 管理入口是一套内置 WebUI。网络配置保存后会热重载。不用重启 QQ。

---

## 4. two-deployments — APK 与 Docker 两条部署路径（7 steps · ~49s）

**信息池**（chapter agent 按需挂角标 / 副标 / pull-quote / mono cue）：
- APK 路径：Android 真机、虚拟机或模拟器必须支持 32 位应用 —— 来源 article §4
- Docker 组成：Redroid Android 13、ARM 转译层、PicoOnebot APK、守护服务 —— 来源 article §4
- 端口：WebUI 6099，OneBot 默认 3001 —— 来源 article §4
- 宿主要求：Binder IPC + 特权容器 —— 来源 article §4
- 就绪时间：首次启动通常 2–5 分钟，状态显示 healthy 后访问 —— 来源 article §4

**开发计划**：

- step 1 (~7s) — APK 与 Docker 分叉路径总览
- step 2 (~7s) — APK 安装目标与“必须支持 32 位”条件
- step 3 (~7s) — 纯 64 位设备无法直接安装的阻断画面
- step 4 (~7s) — Docker 一体化镜像的四层组件剖面
- step 5 (~7s) — Binder 与 privileged 两项宿主前提
- step 6 (~7s) — 6099 与 3001 两个端口对应的服务关系
- step 7 (~7s) — 2–5 分钟初始化、healthy、扫码、填写框架地址的操作链

口播节选：
> 个人设备可以直接装 APK。服务器可以直接跑 Docker。

---

## 5. honest-boundaries — 早期项目的真实边界（5 steps · ~36s）

**信息池**（chapter agent 按需挂角标 / 副标 / pull-quote / mono cue）：
- 环境限制：缺少 Binder 的 Docker Desktop、原生 WSL2、OpenVZ、LXC 不适用 —— 来源 article §5
- 验证状态：尚缺少大规模高并发与长期无人值守验证 —— 来源 article §5
- 群管限制：禁言、移出群聊、管理员、群名片、群公告暂未支持或待验证 —— 来源 article §5
- 消息降级：合并转发发送会退化为单条文本或连续消息 —— 来源 article §5
- 扩展缺口：不支持特种动画表情、戳一戳、频道、红包 —— 来源 article §5

**开发计划**：

- step 1 (~7s) — Binder 作为环境门槛，Docker Desktop 与原生 WSL2 标为不适用
- step 2 (~7s) — “极早期”状态与尚未覆盖的高并发、长期运行验证
- step 3 (~7s) — 群禁言、踢人、群名片等群管理缺口
- step 4 (~7s) — 合并转发“接收解析 / 发送降级”对照
- step 5 (~8s) — 戳一戳、频道、红包等扩展能力边界与风险提示

口播节选：
> 它现在还是早期项目。还没经过大规模高并发。

---

## 6. open-source-cta — 适合谁，以及去哪里找到它（4 steps · ~26s）

**信息池**（chapter agent 按需挂角标 / 副标 / pull-quote / mono cue）：
- 适合方向：轻量、无头、需要连接 OneBot 生态的 QQ 机器人部署 —— 来源 article §2–§4
- 当前版本：v0.13 —— 来源 article §1
- 开源许可：GPL-3.0 —— 来源 article §1
- 合规声明：仅供技术研究与交流，不包含或分发腾讯专有二进制文件 —— 来源 article §1
- 项目地址：github.com/Exmeaning/PicoOnebot —— 来源 article §1

**开发计划**：

- step 1 (~6s) — 轻量、无头、OneBot 生态三个目标汇聚到 PicoOnebot
- step 2 (~6s) — 适用场景与早期项目风险的最终选择卡
- step 3 (~7s) — v0.13、Kotlin、GPL-3.0 与合规声明
- step 4 (~7s) — 项目 Logo、完整 GitHub 地址与“代码 / 文档”双入口收尾

口播节选：
> 你想要轻量无头部署？还要接 OneBot 生态？那就试试 PicoOnebot。

---

## 素材清单

### 1. watch-to-onebot
- ✓ PicoOnebot 横版 Logo（`docs/logo.png`）
- ✓ PicoOnebot 应用图标（`app/mixin/pico_icon_144.png`）
- ✓ 项目架构事实与真实技术名词（`article.md` / 仓库文档）

### 2. light-protocol
- ✓ 资源占用数字与通信模式（`article.md`）
- ✓ 框架名称与协议关系（仓库 README / 文档）
- ⚠️ NoneBot2、Koishi、AstrBot 品牌 Logo（未提供；默认使用纯文字）

### 3. messages-webui
- ✓ WebUI 页面结构与功能文案（`web/src/pages/` / `docs/config/webui.md`）
- ⚠️ 真实 WebUI 运行截图或录屏（未提供；可从仓库前端本地运行后采集）
- ⚠️ 真实登录二维码必须脱敏（默认不使用）

### 4. two-deployments
- ✓ Docker 命令、端口、架构资料（`README.md` / `docs/deploy/docker.md`）
- ✓ Android / Docker 架构可由真实仓库信息绘制为图形
- ⚠️ Android 真机安装画面（未提供；默认使用设备轮廓示意）

### 5. honest-boundaries
- ✓ 限制条件与不支持清单（`README.md` / `article.md`）
- ✓ 不需要外部图片，以能力矩阵和环境关系呈现

### 6. open-source-cta
- ✓ PicoOnebot Logo（`docs/logo.png`）
- ✓ GitHub 项目地址与许可证信息（仓库元数据）
- ⚠️ GitHub 页面实时截图（可选；默认使用项目地址与仓库事实）
