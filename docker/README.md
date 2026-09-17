# redroid 部署

这套 Compose 在 Linux Docker 中运行 Android 13 + PicoOnebot,数据卷会保留 QQ 登录态。

## 前置条件

1. 宿主内核有 binder/binderfs:`test -e /dev/binder`。
2. 已安装 Docker Compose v2。
3. 已通过 Waydroid 的 `waydroid_script` 安装 `libndk_translation`。
4. 已有构建好的 `PicoOnebot_*.apk`。

## 启动

```bash
cp docker/.env.example docker/.env
# 编辑 PICO_APK_PATH;相对路径以 docker/compose.yml 所在目录为基准

sudo docker/prepare-libndk.sh /var/lib/waydroid/overlay/system
sudo docker compose --env-file docker/.env -f docker/compose.yml up -d --build

sudo docker compose --env-file docker/.env -f docker/compose.yml \
  exec picoctl /opt/pico-onebot/picoctl.sh qr
```

默认映射 ADB `5555`、OneBot `3001`、WebUI `6099`;WebUI 初始密码是
`picopico`,首次进入时必须设置新密码。OneBot 不预置连接,需要在控制台添加后才会监听 `3001`。
设置密码后还需要在 `docker/.env` 中配置 `PICO_WEBUI_PASSWORD`,供 `picoctl` 调用控制台 API。
`PICO_DOCKER_SUBNET` 和三个静态地址可在
`docker/.env` 修改;这里不用 Docker 内置 DNS,因为部分 WSL2 内核组合上容器间
DNS 不稳定。

常用检查:

```bash
docker compose --env-file docker/.env -f docker/compose.yml ps
docker compose --env-file docker/.env -f docker/compose.yml logs -f picoctl
docker compose --env-file docker/.env -f docker/compose.yml \
  exec picoctl /opt/pico-onebot/picoctl.sh status
adb connect 127.0.0.1:5555
```

升级 APK 会先卸载旧包并清除登录态:

```bash
docker compose --env-file docker/.env -f docker/compose.yml \
  run --rm picoctl install /opt/pico-onebot/PicoOnebot.apk
```
