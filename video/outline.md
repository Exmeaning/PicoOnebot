# Video Outline

> **标题**：免 Root 手机端挂 AstrBot 的方法
> **主题**：`blueprint`（蓝图）—— 深藏青底、制图青与工程网格，突出连接路径和配置步骤
> **总时长**：约 2 分 58 秒（口播约 709 字 ÷ 4 字/秒）
> **章节数**：5 章 / 24 步

---

## 1. mobile-route — 手机端的 NapCat 替代思路（4 steps · ~31s）

**信息池**（chapter agent 按需挂角标 / 副标 / pull-quote / mono cue）：
- 视频命题：免 Root、免 Xposed，用旧 Android 手机给 AstrBot 提供 QQ 接入 —— 来源 article §1
- 项目定位：PicoOnebot 基于官方手表 QQ，对外提供 OneBot v11 —— 来源 article §1
- 对照关系：NapCat 功能更完整、更适合桌面端；PicoOnebot 聚焦手机与轻量场景 —— 来源 article §1 / §5
- 部署分工：PicoOnebot 运行在 Android 手机，AstrBot 运行在电脑或服务器 —— 来源 article §1

**开发计划**：

- step 1 (~7s) — 旧 Android 手机与标题“免 Root 手机端挂 AstrBot 的方法”占据主舞台
- step 2 (~8s) — Root、Xposed 两项传统门槛被划去，PicoOnebot Logo 成为焦点
- step 3 (~8s) — 手表 QQ、PicoOnebot、OneBot v11 三层关系剖面
- step 4 (~8s) — NapCat 桌面路线与 PicoOnebot 手机路线的适用场景对照

口播节选：
> 旧安卓机还在吃灰？拿它给 AstrBot 挂个 QQ。不用 Root，也不用 Xposed。

---

## 2. phone-setup — APK 安装与扫码登录（5 steps · ~39s）

**信息池**（chapter agent 按需挂角标 / 副标 / pull-quote / mono cue）：
- 下载入口：GitHub Releases 的最新 PicoOnebot APK —— 来源 article §2
- 架构前提：手表 QQ 底包依赖 `armeabi-v7a`，设备必须支持 32 位应用 —— 来源 article §2
- WebUI 入口：同一局域网访问 `http://<手机IP>:6099` —— 来源 article §2
- 初始认证：默认密码 `picopico`，首次登录后修改 —— 来源 article §2
- QQ 登录：WebUI 扫码页展示登录二维码，由另一台已登录 QQ 的设备确认 —— 来源 article §2

**开发计划**：

- step 1 (~8s) — GitHub Releases、PicoOnebot APK 与“安装 / 打开”操作路径
- step 2 (~8s) — 32 位支持条件与纯 64 位机型的兼容分界
- step 3 (~8s) — 手机局域网 IP、6099 端口与浏览器访问关系
- step 4 (~7s) — WebUI 登录画面、初始密码与首次改密提示
- step 5 (~8s) — 本地采集的扫码登录页面与 QQ 上线状态

口播节选：
> 启动后把基础权限给它。再查一下手机的局域网 IP。浏览器打开 IP 加六零九九。

---

## 3. astrbot-side — AstrBot 创建 OneBot 实例（5 steps · ~38s）

**信息池**（chapter agent 按需挂角标 / 副标 / pull-quote / mono cue）：
- 角色分工：手机提供 QQ 协议端，AstrBot 位于电脑或服务器 —— 来源 article §1 / §3
- 创建入口：AstrBot WebUI → 机器人 → 创建 OneBot v11 实例 —— 来源 article §3
- 监听参数：反向 WebSocket 主机 `0.0.0.0`，端口 `6199` —— 来源 article §3
- 鉴权规则：Token 可自定义，但 AstrBot 与 PicoOnebot 必须一致 —— 来源 article §3 / §4
- Docker 条件：AstrBot 容器需要向宿主机映射 6199 端口 —— 来源 article §3

**开发计划**：

- step 1 (~7s) — 手机端 PicoOnebot 与电脑端 AstrBot 的职责分区图
- step 2 (~8s) — AstrBot WebUI 中“机器人 / 创建 / OneBot v11”的操作导航
- step 3 (~8s) — `0.0.0.0`、`6199` 与 Token 三项配置表
- step 4 (~7s) — AstrBot 监听 6199、等待手机连接的网络关系
- step 5 (~8s) — Docker 容器内外的 6199 端口映射对照

口播节选：
> 打开 AstrBot 的 WebUI。进入“机器人”。创建一个 OneBot v11 实例。

---

## 4. connect-test — PicoOnebot 回连与验证（7 steps · ~48s）

**信息池**（chapter agent 按需挂角标 / 副标 / pull-quote / mono cue）：
- PicoOnebot 入口：WebUI → 网络配置 → 添加反向 WebSocket —— 来源 article §4
- 目标模板：`ws://<AstrBot所在IP>:6199/ws` —— 来源 article §4
- IP 选择：局域网部署填写 AstrBot 电脑的局域网 IP —— 来源 article §4
- 常见错误：手机与 AstrBot 不在同一进程时，`127.0.0.1` 指向手机自身 —— 来源 article §4
- 保存行为：配置保存后热重载，无需重启 QQ —— 来源 article §4
- 成功标志：AstrBot 日志出现 `aiocqhttp(OneBot v11) 适配器已连接` —— 来源 article §5

**开发计划**：

- step 1 (~7s) — 本地采集的 PicoOnebot“网络配置”页面与反向 WebSocket 入口
- step 2 (~7s) — 完整目标地址模板 `ws://<AstrBot IP>:6199/ws`
- step 3 (~7s) — 手机 IP、电脑局域网 IP、6199 端口组成的连接链路
- step 4 (~7s) — `127.0.0.1` 实际回到手机自身的错误路径
- step 5 (~6s) — 两端 Token 一致性检查与保存操作
- step 6 (~7s) — “网络配置已保存并热重载”状态与无需重启 QQ 的提示
- step 7 (~7s) — AstrBot 已连接日志、群聊唤醒词与正常回复的验证结果

口播节选：
> 别顺手填一二七点零点零点一。那是手机自己。Token 两边要完全一样。

---

## 5. fit-and-limit — 它适合什么场景（3 steps · ~22s）

**信息池**（chapter agent 按需挂角标 / 副标 / pull-quote / mono cue）：
- 定位边界：PicoOnebot 不是 NapCat 的全功能复刻 —— 来源 article §5
- 已知缺口：群禁言、踢人、群名片、群公告等能力暂未支持或待验证 —— 来源 article §5
- 推荐场景：免 Root 手机、备用 Android 设备、轻量 OneBot 接入 —— 来源 article §5
- 项目入口：github.com/Exmeaning/PicoOnebot —— 来源 article §2 / 项目地址

**开发计划**：

- step 1 (~7s) — NapCat 全功能桌面路线与 PicoOnebot 轻量手机路线的最终选择
- step 2 (~7s) — “群管理未齐 / 基础聊天可用”的能力边界
- step 3 (~8s) — 旧手机连接 AstrBot 的完整链路、项目地址与安装文档收尾

口播节选：
> 它不是 NapCat 的完整复刻。群管理能力还不齐。这条路确实够省事。

---

## 素材清单

### 1. mobile-route
- ✓ PicoOnebot Logo（`docs/logo.png`）
- ✓ PicoOnebot 应用图标（`app/mixin/pico_icon_144.png`）
- ✓ 项目定位与 NapCat 对照事实（`README.md` / `article.md`）
- ⚠️ NapCat Logo 不使用，避免无授权素材；画面采用纯文字路线标签

### 2. phone-setup
- ✓ APK 安装与 32 位兼容信息（`docs/deploy/apk.md`）
- ✓ PicoOnebot WebUI 可从当前仓库本地运行后采集
- ✓ 扫码页使用无真实账号、无有效二维码的脱敏状态

### 3. astrbot-side
- ✓ AstrBot 配置字段与端口信息（`docs/frameworks/astrbot.md`）
- ⚠️ 仓库内没有 AstrBot WebUI 截图；默认使用真实字段组成的界面示意，不伪造账号或状态数据

### 4. connect-test
- ✓ PicoOnebot 网络配置页面可从当前仓库本地运行后采集
- ✓ 完整 URL、Token 规则与成功日志来自官方项目文档
- ✓ 局域网关系使用真实地址模板，不展示真实 Token

### 5. fit-and-limit
- ✓ 能力边界来自 `README.md` 与项目文档
- ✓ GitHub 项目地址、现有 Logo 与安装文档入口
