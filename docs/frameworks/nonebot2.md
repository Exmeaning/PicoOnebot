# 对接 NoneBot2

[NoneBot2](https://nonebot.dev/) 是基于 Python 构建的现代化异步跨平台机器人开发框架。本文介绍如何通过 OneBot v11 适配器与 PicoOnebot 进行对接。

---

## 1. 安装 NoneBot2 与 OneBot 适配器

在 NoneBot2 项目根目录下安装 OneBot v11 适配器驱动：

::: code-group

```bash [nb-cli (推荐)]
nb adapter install nonebot-adapter-onebot
```

```bash [poetry]
poetry add nonebot-adapter-onebot
```

```bash [pip]
pip install nonebot-adapter-onebot
```

:::

---

## 2. 配置 NoneBot2 (`.env` 或 `.env.prod`)

NoneBot2 作为反向 WebSocket 服务端运行。打开项目的 `.env.prod` 文件，确保配置了 WebSocket Reverse 驱动与监听端口：

```ini
DRIVER=~fastapi+~httpx+~websockets

HOST=0.0.0.0
PORT=8080

# 可选：设置连接鉴权 Token
# ONEBOT_ACCESS_TOKEN="your_secure_token"
```

在 `bot.py` 中注册 OneBot v11 适配器：

```python
import nonebot
from nonebot.adapters.onebot.v11 import Adapter as OneBotV11Adapter

nonebot.init()

driver = nonebot.get_driver()
driver.register_adapter(OneBotV11Adapter)

nonebot.load_from_toml("pyproject.toml")

if __name__ == "__main__":
    nonebot.run()
```

---

## 3. 在 PicoOnebot 中配置反向 WebSocket

1. 登录 PicoOnebot Web 控制台（`http://<IP>:6099`）；
2. 进入「网络配置」面板，点击「添加连接」；
3. 选择协议为 **反向 WebSocket**；
4. **URL** 填写 NoneBot2 的 OneBot v11 路径：
   ```text
   ws://<NoneBot所在IP>:8080/onebot/v11/ws
   ```
   > 若两者部署在同一台宿主机且使用了 Docker 容器网络，请填写宿主机局域网 IP 或 Docker 网关地址（如 `172.17.0.1`），避免直接使用 `127.0.0.1`。
5. **Access Token**：若在 NoneBot2 中配置了 Token，请在此填写相同值；
6. 保存配置。

---

## 4. 验证连接

1. 启动 NoneBot2 实例；
2. 观察 NoneBot2 终端控制台输出，出现类似如下日志即表示对接成功：
   ```text
   [INFO] nonebot | OneBot V11 | Bot <QQ号> connected
   ```
3. 发送群聊或私聊消息，验证插件响应是否正常。
