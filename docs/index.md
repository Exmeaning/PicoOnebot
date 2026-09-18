---
layout: home

hero:
  name: PicoOnebot
  text: 轻量、零侵入的手表 QQ OneBot v11
  tagline: 基于官方手表 QQ 客户端的底层 Hook 实现，开销低，支持 APK 直装与 Docker 一体化容器运行。
  image:
    src: /logo.png
    alt: PicoOnebot
  actions:
    - theme: brand
      text: 快速开始
      link: /guide/quick-start
    - theme: alt
      text: 部署方案
      link: /deploy/
    - theme: alt
      text: GitHub 仓库
      link: https://github.com/exmeaning/PicoOnebot

features:
  - title: 原生轻量 Hook
    details: 直接注入官方手表 QQ 客户端，无需庞大的桌面版运行时，内存与 CPU 占用显著降低。
  - title: Docker 一体化交付
    details: 内置 Redroid (Android 13) 与 ARM 转译层，单命令拉起完整无头容器环境，开箱即用。
  - title: 标准 OneBot v11
    details: 支持 HTTP 上报、正向 WebSocket 与反向 WebSocket，可对接 NoneBot2、Koishi、AstrBot 等主流框架。
  - title: 现代 WebUI 控制台
    details: 内置管理后台，支持扫码登录、在线修改网络配置、实时状态监控与密码鉴权管理。
  - title: 守护与自恢复
    details: 内置容器看护进程与健康检查探针，支持应用崩溃自动拉起与异常恢复。
  - title: 跨架构与云原生
    details: 适配 arm64 与 amd64 架构，支持实体机、Docker、K3s/Kubernetes 等多种生产环境。
---
