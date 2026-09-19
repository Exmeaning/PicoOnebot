import type { ChapterStepProps } from "../../registry/types";
import "./ConnectTwoSides.css";

function MiniPhone() {
  return <div className="ct-phone"><i /><img src="/assets/pico-icon.png" alt="" /><strong>PicoOnebot</strong><span className="mono">PHONE</span></div>;
}
function MiniAstrBot() {
  return <div className="ct-astr"><i /><i /><i /><strong>AstrBot</strong><span className="mono">SERVER</span></div>;
}

export default function ConnectTwoSides({ step }: ChapterStepProps) {
  if (step === 0) return (
    <section className="ct-scene ct-network">
      <div className="ct-shot"><img src="/assets/webui-network.png" alt="PicoOnebot 网络配置界面" /><div className="ct-shot-dim" /><div className="ct-shot-focus" /></div>
      <div className="ct-network-copy"><span className="mono">PICOONEBOT / NETWORK</span><h2>新增一个<br /><em>反向 WebSocket</em></h2><div className="ct-path">网络配置 → 反向 WebSocket → 新增</div></div>
    </section>
  );

  if (step === 1) return (
    <section className="ct-scene ct-address">
      <div className="ct-address-title"><span className="mono">TARGET URL</span><h2>地址、路径，跟对方一致。</h2></div>
      <div className="ct-url">
        <div className="ct-url-part ct-url-protocol"><span className="mono">PROTOCOL</span><strong>ws://<small>或</small>wss://</strong></div>
        <i>+</i>
        <div className="ct-url-part ct-url-ip"><span className="mono">ROBOT ENDPOINT</span><strong>{`<ROBOT_HOST>`}</strong></div>
        <i>+</i>
        <div className="ct-url-part ct-url-port"><span className="mono">PATH</span><strong>{`/<WS_PATH>`}</strong></div>
      </div>
      <div className="ct-url-final mono">{`ws[s]://<ROBOT_HOST>/<WS_PATH>`}</div>
      <svg viewBox="0 0 1720 280" aria-hidden="true"><path d="M110 35 V165 H1610 V35"/><path d="M845 165 V245"/></svg>
    </section>
  );

  if (step === 2) return (
    <section className="ct-scene ct-link">
      <div className="ct-link-copy"><span className="mono">REACHABLE IS ENOUGH</span><h2>内网、公网，<br /><em>都能连。</em></h2><p>PicoOnebot 主动向机器人地址发起连接。</p></div>
      <div className="ct-link-map">
        <MiniPhone />
        <div className="ct-route-choice">
          <div><span className="mono">LAN</span><strong>192.168.x.x</strong><small>同一局域网</small></div>
          <b>或</b>
          <div className="is-public"><span className="mono">INTERNET</span><strong>{`<PUBLIC_HOST>`}</strong><small>公网可访问入口</small></div>
        </div>
        <MiniAstrBot />
        <svg viewBox="0 0 1100 600" aria-hidden="true"><path d="M210 300 H420 C500 300 500 165 585 165 H900"/><path d="M420 300 C500 300 500 440 585 440 H900"/><circle r="11"><animateMotion dur="1.9s" repeatCount="indefinite" path="M210 300 H420 C500 300 500 440 585 440 H900"/></circle></svg>
        <div className="ct-link-address mono"><span>OUTBOUND</span><strong>PHONE → ROBOT</strong></div>
      </div>
    </section>
  );

  if (step === 3) return (
    <section className="ct-scene ct-localhost">
      <div className="ct-local-title"><span className="mono">SAME ADDRESS / DIFFERENT FIELD</span><h2><em>127.0.0.1</em><br />要看填在哪。</h2><p>进 Pico 控制台可以；指向外部机器人不行。</p></div>
      <div className="ct-local-map ct-local-compare">
        <div className="ct-local-row ct-local-row--ok"><span className="mono">PHONE BROWSER</span><strong className="mono">127.0.0.1:6099</strong><b>→ Pico WebUI</b><i>✓</i></div>
        <div className="ct-local-row ct-local-row--bad"><span className="mono">REVERSE WS TARGET</span><strong className="mono">127.0.0.1</strong><b>→ 手机自己</b><i>×</i></div>
        <div className="ct-local-arrow">↓</div>
        <div className="ct-local-real"><span className="mono">EXTERNAL ROBOT</span><strong>填写机器人的真实可达地址</strong><i>✓</i></div>
        <svg viewBox="0 0 900 720" aria-hidden="true"><path d="M130 165 H760"/><path d="M130 385 H760"/><path d="M450 445 V595"/></svg>
      </div>
    </section>
  );

  if (step === 4) return (
    <section className="ct-scene ct-token">
      <div className="ct-token-copy"><span className="mono">OPTIONAL TOKEN</span><h2>设置了，就要<br /><em>一模一样。</em></h2><p>对方未设置 Token 时，PicoOnebot 留空。</p></div>
      <div className="ct-token-pair">
        <div className="ct-token-card"><MiniPhone /><span className="ct-token-label mono">TOKEN</span><strong className="mono">SAME_TOKEN</strong></div>
        <div className="ct-token-match"><span>✓</span><b className="mono">MATCH</b></div>
        <div className="ct-token-card"><MiniAstrBot /><span className="ct-token-label mono">TOKEN</span><strong className="mono">SAME_TOKEN</strong></div>
        <svg viewBox="0 0 1000 520" aria-hidden="true"><path d="M245 380 H755"/><circle r="10"><animateMotion dur="1.8s" repeatCount="indefinite" path="M245 380 H755"/></circle><circle r="10"><animateMotion begin=".9s" dur="1.8s" repeatCount="indefinite" path="M755 380 H245"/></circle></svg>
      </div>
    </section>
  );

  if (step === 5) return (
    <section className="ct-scene ct-run">
      <div className="ct-run-head"><span className="mono">HOT RELOAD</span><h2>保存就生效。<em>不用重启 QQ。</em></h2></div>
      <div className="ct-run-map"><MiniPhone /><div className="ct-run-line"><i /><i /><i /><span className="mono">REVERSE WEBSOCKET</span></div><MiniAstrBot /></div>
      <div className="ct-run-state"><span className="mono">CONFIG SAVED</span><strong><i /> ONLINE</strong></div>
    </section>
  );

  if (step === 6) return (
    <section className="ct-scene ct-success">
      <div className="ct-success-copy"><span className="mono">CONNECTED</span><h2>日志出现<br /><em>「已连接」</em></h2><p>再去群里发个唤醒词，能正常回复就挂好了。</p></div>
      <div className="ct-success-board">
        <div className="ct-log-head mono"><i /><i /><i /> ROBOT FRAMEWORK / STATUS</div>
        <div className="ct-log-line"><span className="mono">INFO</span><strong>OneBot v11 连接已建立</strong><b className="mono">CONNECTED</b></div>
        <div className="ct-message"><img src="/assets/pico-icon.png" alt="" /><div><span className="mono">QQ MESSAGE</span><strong>你好，AstrBot！</strong></div><i>→</i><div className="ct-bot-reply"><span className="mono">ASTRBOT</span><strong>连接成功 ✦</strong></div></div>
        <div className="ct-success-stamp"><span>✓</span><b>手机协议端<br />已挂载</b></div>
      </div>
    </section>
  );
  return null;
}
