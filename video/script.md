旧安卓机还在吃灰？
拿它给 AstrBot 挂个 QQ。
不用 Root，也不用 Xposed。

---

这个方案叫 PicoOnebot。
它跑在官方手表 QQ 里面。
对外提供 OneBot v11。

---

NapCat 的功能更全。
但它更适合桌面端。
PicoOnebot 走的是手机路线。

---

先去 GitHub Releases。
下载最新版 APK。
项目名是 PicoOnebot。
装好后直接打开。

---

这里有个前提。
手机必须支持三十二位应用。
纯六十四位机型可能装不上。

---

启动后把基础权限给它。
再查一下手机的局域网 IP。
浏览器打开 IP 加六零九九。

---

初始密码是 picopico。
第一次登录会要求改密码。
改完就去扫码登录 QQ。

---

这里别搞混。
PicoOnebot 跑在手机上。
AstrBot 跑在电脑或服务器上。

---

打开 AstrBot 的 WebUI。
进入“机器人”。
创建一个 OneBot v11 实例。

---

反向 WS 主机填零点零点零点零。
端口填六一九九。
Token 自己设一个。

---

AstrBot 跑在 Docker？
六一九九也要映射出来。
没映射，手机会连不上。

---

再回到 PicoOnebot。
打开“网络配置”。
添加一条反向 WebSocket。

---

目标地址指向 AstrBot。
用电脑的局域网 IP。
端口六一九九，路径是 ws。

---

别顺手填一二七点零点零点一。
那是手机自己。
Token 两边要完全一样。

---

保存配置就会热重载。
不用重启 QQ。

---

去 AstrBot 看日志。
出现“适配器已连接”，
这条链路就通了。

---

现在去群里发个唤醒词。
AstrBot 能正常回复。
这台手机就挂好了。

---

它不是 NapCat 的完整复刻。
群管理能力还不齐。
旧手机免 Root 接 AstrBot。
这条路确实够省事。

---

项目地址就在 GitHub。
去搜 PicoOnebot。
安装包和对接文档都在那里。
