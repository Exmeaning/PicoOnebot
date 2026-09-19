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
      <div className="ct-address-title"><span className="mono">TARGET URL</span><h2>AstrBot 在哪里？</h2></div>
      <div className="ct-url">
        <div className="ct-url-part ct-url-protocol"><span className="mono">PROTOCOL</span><strong>ws://</strong></div>
        <i>+</i>
        <div className="ct-url-part ct-url-ip"><span className="mono">ASTRBOT LAN IP</span><strong>192.168.1.20</strong></div>
        <i>+</i>
        <div className="ct-url-part ct-url-port"><span className="mono">PORT + PATH</span><strong>:6199/ws</strong></div>
      </div>
      <div className="ct-url-final mono">ws://192.168.1.20:6199/ws</div>
      <svg viewBox="0 0 1720 280" aria-hidden="true"><path d="M110 35 V165 H1610 V35"/><path d="M845 165 V245"/></svg>
    </section>
  );

  if (step === 2) return (
    <section className="ct-scene ct-link">
      <div className="ct-link-copy"><span className="mono">LAN ROUTE</span><h2>填<em>电脑</em>的<br />局域网 IP。</h2><p>手机与 AstrBot 要能互相访问。</p></div>
      <div className="ct-link-map">
        <MiniPhone />
        <div className="ct-router"><i /><i /><b className="mono">LAN</b><span>同一局域网</span></div>
        <MiniAstrBot />
        <svg viewBox="0 0 1100 600" aria-hidden="true"><path d="M210 300 H470"/><path d="M630 300 H900"/><circle r="11"><animateMotion dur="1.7s" repeatCount="indefinite" path="M210 300 H900"/></circle></svg>
        <div className="ct-link-address mono"><span>ASTRBOT</span><strong>192.168.1.20</strong><b>:6199/ws</b></div>
      </div>
    </section>
  );

  if (step === 3) return (
    <section className="ct-scene ct-localhost">
      <div className="ct-local-title"><span className="mono">COMMON MISTAKE</span><h2>不要填<br /><em>127.0.0.1</em></h2><p>在手机上，它只会回到手机自己。</p></div>
      <div className="ct-local-map">
        <MiniPhone />
        <svg viewBox="0 0 850 650" aria-hidden="true"><path d="M245 330 C720 330 735 90 430 90 C150 90 110 425 335 500"/><path d="m305 475 42 31-50 16"/></svg>
        <div className="ct-local-loop mono">LOOPBACK</div>
        <div className="ct-local-address mono">127.0.0.1</div>
        <div className="ct-local-server"><MiniAstrBot /><span>没有到达</span></div>
        <div className="ct-local-cross">×</div>
      </div>
    </section>
  );

  if (step === 4) return (
    <section className="ct-scene ct-token">
      <div className="ct-token-copy"><span className="mono">SHARED TOKEN</span><h2>两边必须<br /><em>一模一样。</em></h2></div>
      <div className="ct-token-pair">
        <div className="ct-token-card"><MiniPhone /><span className="ct-token-label mono">TOKEN</span><strong className="mono">pico_bot_01</strong></div>
        <div className="ct-token-match"><span>✓</span><b className="mono">MATCH</b></div>
        <div className="ct-token-card"><MiniAstrBot /><span className="ct-token-label mono">TOKEN</span><strong className="mono">pico_bot_01</strong></div>
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
        <div className="ct-log-head mono"><i /><i /><i /> ASTRBOT / LOGS</div>
        <div className="ct-log-line"><span className="mono">INFO</span><strong>aiocqhttp (OneBot v11) 适配器已连接</strong><b className="mono">CONNECTED</b></div>
        <div className="ct-message"><img src="/assets/pico-icon.png" alt="" /><div><span className="mono">QQ MESSAGE</span><strong>你好，AstrBot！</strong></div><i>→</i><div className="ct-bot-reply"><span className="mono">ASTRBOT</span><strong>连接成功 ✦</strong></div></div>
        <div className="ct-success-stamp"><span>✓</span><b>手机协议端<br />已挂载</b></div>
      </div>
    </section>
  );
  return null;
}
