# 通用 OneBot v11 对接规范

PicoOnebot 严格实现标准 [OneBot v11 规范](https://github.com/botuniverse/onebot-11)。任何兼容 OneBot v11 的客户端、SDK 或自研框架均可无缝对接。

---

## 协议接口与数据格式

### 1. 通信协议支持

- **反向 WebSocket (WebSocket Reverse)**：PicoOnebot 主动连接至框架提供的 WebSocket 服务端，支持心跳发送与断线自动重连；
- **正向 WebSocket (WebSocket Client)**：由框架主动连接 PicoOnebot 开放的端口（默认 `3001`）；
- **HTTP POST 上报**：PicoOnebot 收到事件后以 HTTP POST 方式上报 JSON 数据。

### 2. 消息事件格式 (Event Format)

收到的事件均为标准 JSON 结构，包含 `post_type`（`message`, `notice`, `request`, `meta_event`）等字段：

```json
{
  "time": 1711234567,
  "self_id": 123456789,
  "post_type": "message",
  "message_type": "group",
  "sub_type": "normal",
  "message_id": 10001,
  "group_id": 987654321,
  "user_id": 1122334455,
  "anonymous": null,
  "message": "Hello PicoOnebot",
  "raw_message": "Hello PicoOnebot",
  "font": 0,
  "sender": {
    "user_id": 1122334455,
    "nickname": "Tester",
    "role": "member"
  }
}
```

### 3. API 调用格式 (Action API)

通过 WebSocket 发送动作请求时，格式如下：

```json
{
  "action": "send_group_msg",
  "params": {
    "group_id": 987654321,
    "message": "收到测试消息"
  },
  "echo": "msg_001"
}
```

响应结果：

```json
{
  "status": "ok",
  "retcode": 0,
  "data": {
    "message_id": 10002
  },
  "echo": "msg_001"
}
```

---

## 常见适配要点

- **CQ 码与消息段支持**：支持纯文本、表情（`face`）、图片（`image`）等常见消息段解析与发送；
- **跨机器网络互通**：若 PicoOnebot 容器与机器人框架分布在不同物理机或不同 Docker 容器中，反向 WebSocket 地址中请勿使用 `127.0.0.1`，应使用局域网 IP 或同一 Docker 网络内的服务名称。
