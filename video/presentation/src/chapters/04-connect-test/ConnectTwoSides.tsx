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
    <section className="ct-scene ct-quick">
      <div className="ct-quick-copy"><span className="mono">TARGET URL</span><h2>填机器人的<br /><em>真实地址。</em></h2><p>局域网与公网入口都可以。</p></div>
      <div className="ct-quick-board">
        <div className="ct-quick-field"><span>反向 WebSocket 地址</span><strong className="mono">{`ws[s]://<ROBOT_HOST>/<WS_PATH>`}</strong></div>
        <div className="ct-quick-options"><div><span className="mono">LAN</span><b>192.168.x.x</b></div><i>或</i><div className="is-public"><span className="mono">INTERNET</span><b className="mono">PUBLIC_HOST</b></div></div>
        <div className="ct-quick-warning"><span className="mono">× 127.0.0.1</span><strong>外部机器人不要填本机回环地址</strong></div>
        <svg viewBox="0 0 900 650" aria-hidden="true"><path d="M450 170 V285"/><path d="M225 285 H675"/><path d="M225 285 V410"/><path d="M675 285 V410"/></svg>
      </div>
    </section>
  );

  if (step === 2) return (
    <section className="ct-scene ct-token">
      <div className="ct-token-copy"><span className="mono">ACCESS TOKEN</span><h2>设置了，就要<br /><em>一模一样。</em></h2><p>对方未设置 Token 时，PicoOnebot 留空。</p></div>
      <div className="ct-token-pair">
        <div className="ct-token-card"><MiniPhone /><span className="ct-token-label mono">TOKEN</span><strong className="mono">SAME_TOKEN</strong></div>
        <div className="ct-token-match"><span>✓</span><b className="mono">MATCH</b></div>
        <div className="ct-token-card"><MiniAstrBot /><span className="ct-token-label mono">TOKEN</span><strong className="mono">SAME_TOKEN</strong></div>
        <svg viewBox="0 0 1000 520" aria-hidden="true"><path d="M245 380 H755"/><circle r="10"><animateMotion dur="1.8s" repeatCount="indefinite" path="M245 380 H755"/></circle><circle r="10"><animateMotion begin=".9s" dur="1.8s" repeatCount="indefinite" path="M755 380 H245"/></circle></svg>
      </div>
    </section>
  );

  if (step === 3) return (
    <section className="ct-scene ct-run">
      <div className="ct-run-head"><span className="mono">HOT RELOAD</span><h2>保存就生效。<em>不用重启 QQ。</em></h2></div>
      <div className="ct-run-map"><MiniPhone /><div className="ct-run-line"><i /><i /><i /><span className="mono">AUTO RECONNECT</span></div><MiniAstrBot /></div>
      <div className="ct-run-state"><span className="mono">CONFIG SAVED</span><strong><i /> ONLINE</strong></div>
    </section>
  );

  if (step === 4) return (
    <section className="ct-scene ct-success">
      <div className="ct-success-copy"><span className="mono">CONNECTED</span><h2>连接建立，<br /><em>发消息测试。</em></h2><p>机器人能正常回复，这台手机就挂好了。</p></div>
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
