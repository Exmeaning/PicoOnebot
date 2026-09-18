# Kubernetes / K3s 部署

在云原生 Kubernetes 或轻量级 K3s 集群中，可通过本仓库提供的 Deployment 与 Service 清单快速交付 PicoOnebot。

---

## 节点前置要求

1. **节点内核 Binder 支持**：Pod 调度的物理/虚拟节点必须支持 Binder（支持 `binderfs` 或具备 `0666` 权限的 `/dev/binder` 设备节点）。
   建议在目标集群节点上运行检测脚本：
   ```bash
   bash docker/scripts/pico-host-check.sh
   ```
2. **特权容器支持**：集群的 PodSecurity / SecurityContextConstraints 需允许运行 `privileged: true` 容器。

---

## 清单配置 (`docker/pico-k8s.yaml`)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: pico-onebot
  namespace: default
  labels:
    app: pico-onebot
spec:
  replicas: 1
  strategy:
    type: Recreate
  selector:
    matchLabels:
      app: pico-onebot
  template:
    metadata:
      labels:
        app: pico-onebot
    spec:
      containers:
      - name: pico-onebot
        image: ghcr.io/exmeaning/pico-onebot:latest
        imagePullPolicy: Always
        securityContext:
          privileged: true
        env:
        - name: PICO_LOG_KMSG
          value: "1"
        ports:
        - containerPort: 6099
          name: webui
        - containerPort: 3001
          name: onebot
        - containerPort: 5555
          name: adb
        # 首次初始化包含 Android 启动及 APK 安装，配置适当的启动探测超时
        startupProbe:
          tcpSocket:
            port: 6099
          periodSeconds: 10
          failureThreshold: 48
        readinessProbe:
          tcpSocket:
            port: 6099
          periodSeconds: 20
        volumeMounts:
        - name: data
          mountPath: /data
        # SurfaceFlinger 与 Gralloc 所需的共享内存
        - name: dshm
          mountPath: /dev/shm
      volumes:
      - name: data
        hostPath:
          path: /var/lib/pico-onebot-data
          type: DirectoryOrCreate
      - name: dshm
        emptyDir:
          medium: Memory
          sizeLimit: 1Gi
---
apiVersion: v1
kind: Service
metadata:
  name: pico-onebot
  namespace: default
spec:
  type: NodePort
  selector:
    app: pico-onebot
  ports:
  - name: webui
    port: 6099
    targetPort: 6099
    nodePort: 30099
  - name: onebot
    port: 3001
    targetPort: 3001
    nodePort: 30001
```

---

## 部署与管理

### 1. 应用清单
```bash
kubectl apply -f docker/pico-k8s.yaml
```

### 2. 检查 Pod 状态
由于首次冷启动包含系统引导，Pod 状态将由 `Running (0/1)` 逐步进入 `Running (1/1)` 就绪状态：
```bash
kubectl get pods -l app=pico-onebot -w
```

### 3. 查看日志
```bash
kubectl logs -f deployment/pico-onebot
```

### 4. 访问服务
默认配置为 `NodePort` 暴露：
- **WebUI 控制台**：`http://<节点IP>:30099`（默认密码: `picopico`）
- **OneBot 端口**：`<节点IP>:30001`
