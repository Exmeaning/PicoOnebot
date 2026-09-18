import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'PicoOnebot',
  description: '轻量级、零侵入、原生运行的手表 QQ OneBot v11 实现',
  base: process.env.VITEPRESS_BASE || '/PicoOnebot/',
  cleanUrls: true,
  head: [
    ['link', { rel: 'icon', href: '/logo.png' }]
  ],
  themeConfig: {
    logo: '/logo.png',
    siteTitle: 'PicoOnebot',
    nav: [
      { text: '指南', link: '/guide/introduction', activeMatch: '^/guide/' },
      { text: '部署', link: '/deploy/', activeMatch: '^/deploy/' },
      { text: '配置', link: '/config/webui', activeMatch: '^/config/' },
      { text: '框架对接', link: '/frameworks/nonebot2', activeMatch: '^/frameworks/' },
      { text: '排错与运维', link: '/troubleshooting/faq', activeMatch: '^/troubleshooting/' },
      {
        text: 'Releases',
        link: 'https://github.com/exmeaning/PicoOnebot/releases'
      }
    ],
    sidebar: {
      '/guide/': [
        {
          text: '基础指南',
          items: [
            { text: '项目介绍', link: '/guide/introduction' },
            { text: '快速入门', link: '/guide/quick-start' },
            { text: '架构与运行原理', link: '/guide/architecture' }
          ]
        }
      ],
      '/deploy/': [
        {
          text: '部署方案',
          items: [
            { text: '部署形态总览', link: '/deploy/' },
            { text: 'Android / 模拟器 (APK)', link: '/deploy/apk' },
            { text: 'Docker 一体化容器', link: '/deploy/docker' },
            { text: 'Kubernetes / K3s 部署', link: '/deploy/k8s' },
            { text: 'Linux 宿主 Binder 指南', link: '/deploy/binder' },
            { text: 'WSL2 环境配置', link: '/deploy/wsl2' }
          ]
        }
      ],
      '/config/': [
        {
          text: '配置指南',
          items: [
            { text: 'WebUI 控制台', link: '/config/webui' },
            { text: '网络与 OneBot 上报', link: '/config/network' },
            { text: '环境变量参考', link: '/config/env' }
          ]
        }
      ],
      '/frameworks/': [
        {
          text: '机器人框架对接',
          items: [
            { text: 'NoneBot2', link: '/frameworks/nonebot2' },
            { text: 'Koishi', link: '/frameworks/koishi' },
            { text: 'AstrBot', link: '/frameworks/astrbot' },
            { text: '通用 OneBot v11 适配', link: '/frameworks/generic' }
          ]
        }
      ],
      '/troubleshooting/': [
        {
          text: '排错与运维',
          items: [
            { text: '常见问题 (FAQ)', link: '/troubleshooting/faq' },
            { text: '诊断工具与日志排查', link: '/troubleshooting/doctor' }
          ]
        }
      ]
    },
    search: {
      provider: 'local',
      options: {
        translations: {
          button: {
            buttonText: '搜索文档',
            buttonAriaLabel: '搜索文档'
          },
          modal: {
            noResultsText: '无法找到相关结果',
            resetButtonTitle: '清除查询条件',
            footer: {
              selectText: '选择',
              navigateText: '切换',
              closeText: '关闭'
            }
          }
        }
      }
    },
    socialLinks: [
      { icon: 'github', link: 'https://github.com/exmeaning/PicoOnebot' }
    ],
    footer: {
      message: 'Released under the GPL-3.0 License.',
      copyright: 'Copyright © 2024-present Exmeaning & PicoOnebot Contributors'
    },
    outline: {
      label: '本页目录',
      level: [2, 3]
    },
    docFooter: {
      prev: '上一篇',
      next: '下一篇'
    }
  }
})
