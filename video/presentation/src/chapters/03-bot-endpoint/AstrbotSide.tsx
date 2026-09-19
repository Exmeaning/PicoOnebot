import type { ChapterStepProps } from "../../registry/types";
import "./AstrbotSide.css";

function PhoneNode() {
  return <div className="as-phone"><i /><img src="/assets/pico-icon.png" alt="" /><b>PicoOnebot</b><span className="mono">ANDROID PHONE</span></div>;
}
function ServerNode({ generic = false }: { generic?: boolean }) {
  return <div className="as-server"><i /><i /><i /><strong>{generic ? "机器人框架" : "AstrBot"}</strong><span className="mono">PC / SERVER</span></div>;
}

export default function AstrbotSide({ step }: ChapterStepProps) {
  if (step === 0) return (
    <section className="as-scene as-roles">
      <div className="as-roles-title"><span className="mono">TWO SIDES / ONE LINK</span><h2>手机管 QQ。<br /><em>框架管机器人。</em></h2></div>
      <div className="as-role-map">
        <div className="as-role-side"><PhoneNode /><strong>QQ 协议端</strong><small>手表 QQ / OneBot v11</small></div>
        <svg viewBox="0 0 640 220" aria-hidden="true"><path d="M25 110 H580"/><path d="m560 80 46 30-46 30"/><circle r="10"><animateMotion dur="2.1s" repeatCount="indefinite" path="M25 110 H580"/></circle></svg>
        <div className="as-protocol-badge mono">REVERSE WEBSOCKET</div>
        <div className="as-role-side"><ServerNode generic /><strong>模型 / 插件 / 逻辑</strong><small>AstrBot 等机器人框架</small></div>
      </div>
    </section>
  );

  if (step === 1) return (
    <section className="as-scene as-endpoint">
      <div className="as-endpoint-copy"><span className="mono">ROBOT SIDE</span><h2>只准备一个<br /><em>可访问的入口。</em></h2><p>AstrBot 怎么安装、字段怎么填，这里不展开。</p></div>
      <div className="as-endpoint-stage">
        <div className="as-endpoint-server"><ServerNode /><div className="as-socket"><i /><span className="mono">ONEBOT V11</span><strong>反向 WebSocket</strong><small className="mono">LISTENING</small></div></div>
        <div className="as-endpoint-url mono"><span>ENDPOINT</span><strong>{`ws[s]://<ROBOT_HOST>/<WS_PATH>`}</strong></div>
        <svg viewBox="0 0 900 750" aria-hidden="true"><path d="M720 610 H500 V385"/><path d="m476 410 24-42 24 42"/><circle r="10"><animateMotion dur="1.9s" repeatCount="indefinite" path="M720 610 H500 V385"/></circle></svg>
        <div className="as-endpoint-phone"><PhoneNode /></div>
      </div>
    </section>
  );

  if (step === 2) return (
    <section className="as-scene as-reach">
      <div className="as-reach-copy"><span className="mono">OUTBOUND CONNECTION</span><h2>同一局域网？<br /><em>不是必须。</em></h2><p>手机主动连出去，不需要给手机开放 OneBot 入站端口。</p></div>
      <div className="as-reach-map">
        <div className="as-reach-phone"><PhoneNode /></div>
        <div className="as-globe"><i /><i /><i /><strong className="mono">PUBLIC<br />NETWORK</strong><small>公网可达入口</small></div>
        <div className="as-reach-server"><ServerNode /></div>
        <svg viewBox="0 0 1180 720" aria-hidden="true"><path d="M185 365 H785"/><path d="m765 338 42 27-42 27"/><circle r="11"><animateMotion dur="2.2s" repeatCount="indefinite" path="M185 365 H785"/></circle></svg>
        <div className="as-lan-note"><span className="mono">LAN ALSO WORKS</span><strong>局域网也可以</strong></div>
        <div className="as-retry mono">DISCONNECT → AUTO RETRY</div>
      </div>
    </section>
  );
  return null;
}
