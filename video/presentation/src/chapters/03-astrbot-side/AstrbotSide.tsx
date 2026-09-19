import type { ChapterStepProps } from "../../registry/types";
import "./AstrbotSide.css";

function PhoneNode() {
  return <div className="as-phone"><i /><img src="/assets/pico-icon.png" alt="" /><b>PicoOnebot</b><span className="mono">ANDROID PHONE</span></div>;
}

function ServerNode({ docker = false }: { docker?: boolean }) {
  return <div className={`as-server${docker ? " as-server--docker" : ""}`}><i /><i /><i /><strong>AstrBot</strong><span className="mono">{docker ? "DOCKER CONTAINER" : "PC / SERVER"}</span></div>;
}

export default function AstrbotSide({ step }: ChapterStepProps) {
  if (step === 0) {
    return (
      <section className="as-scene as-roles">
        <div className="as-roles-title"><span className="mono">RUN ON TWO DEVICES</span><h2>手机管 QQ。<br /><em>AstrBot 管机器人。</em></h2></div>
        <div className="as-role-map">
          <div className="as-role-side as-role-side--phone"><PhoneNode /><strong>协议端</strong><small>手表 QQ / OneBot v11</small></div>
          <svg viewBox="0 0 640 220" aria-hidden="true"><path d="M25 110 H580"/><path d="m560 80 46 30-46 30"/><circle r="10"><animateMotion dur="2.1s" repeatCount="indefinite" path="M25 110 H580"/></circle></svg>
          <div className="as-protocol-badge mono">REVERSE WEBSOCKET</div>
          <div className="as-role-side as-role-side--server"><ServerNode /><strong>机器人框架</strong><small>电脑或服务器</small></div>
        </div>
      </section>
    );
  }

  if (step === 1) {
    return (
      <section className="as-scene as-nav">
        <div className="as-nav-copy"><span className="mono">ASTRBOT WEBUI</span><h2>找到<em>「机器人」</em></h2><p>从 AstrBot 控制台开始。</p></div>
        <div className="as-webui-shell">
          <div className="as-webui-sidebar">
            {['仪表盘','聊天','插件','机器人','日志'].map((x,i)=><div key={x} className={i===3?'is-active':''}><i />{x}</div>)}
          </div>
          <div className="as-webui-main"><div className="as-webui-top mono">ASTRBOT / WEBUI</div><div className="as-bot-tile"><span className="mono">BOT ADAPTERS</span><strong>机器人</strong><p>管理消息平台与协议实例</p><button>+ 创建机器人</button></div></div>
          <div className="as-cursor">↗</div>
        </div>
      </section>
    );
  }

  if (step === 2) {
    return (
      <section className="as-scene as-create">
        <div className="as-create-track">
          <div className="as-create-node"><span className="hero-num">01</span><b>创建机器人</b></div>
          <i />
          <div className="as-create-node"><span className="hero-num">02</span><b>选择协议</b></div>
          <i />
          <div className="as-create-node as-create-node--hero"><span className="mono">ADAPTER</span><strong>OneBot<br />v11</strong></div>
        </div>
        <div className="as-create-title"><span className="mono">NEW BOT INSTANCE</span><h2>创建一个<br /><em>OneBot v11</em> 实例</h2></div>
      </section>
    );
  }

  if (step === 3) {
    return (
      <section className="as-scene as-config">
        <div className="as-config-copy"><span className="mono">REVERSE WS LISTENER</span><h2>三个字段，<br />照着填。</h2></div>
        <div className="as-config-board">
          <div className="as-field"><span>反向 WebSocket 主机</span><strong className="mono">0.0.0.0</strong><small>允许手机发起连接</small></div>
          <div className="as-field as-field--port"><span>端口</span><strong className="hero-num">6199</strong><small>监听中</small></div>
          <div className="as-field"><span>Token</span><strong className="mono">YOUR_TOKEN</strong><small>两端保持一致</small></div>
          <svg viewBox="0 0 980 520" aria-hidden="true"><path d="M230 145 H475 V260"/><path d="M750 145 H505 V260"/><circle cx="490" cy="260" r="22"/></svg>
        </div>
      </section>
    );
  }

  if (step === 4) {
    return (
      <section className="as-scene as-docker">
        <div className="as-docker-copy"><span className="mono">WHEN ASTRBOT RUNS IN DOCKER</span><h2>把 <em>6199</em><br />映射出来。</h2><p>端口留在容器里，手机就找不到。</p></div>
        <div className="as-docker-map">
          <div className="as-host-box"><span className="mono">HOST MACHINE</span><div className="as-container-box"><span className="mono">DOCKER</span><ServerNode docker /><div className="as-port-in mono">6199</div></div><div className="as-port-out mono">6199</div></div>
          <PhoneNode />
          <svg viewBox="0 0 1000 700" aria-hidden="true"><path className="as-map-path" d="M830 530 H650 V350 H420"/><path className="as-map-tip" d="m440 325-38 25 38 25"/><circle r="11"><animateMotion dur="2s" repeatCount="indefinite" path="M830 530 H650 V350 H420"/></circle></svg>
          <div className="as-map-label mono">HOST:6199 → CONTAINER:6199</div>
        </div>
      </section>
    );
  }

  return null;
}
