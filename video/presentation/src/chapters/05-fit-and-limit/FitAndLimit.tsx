import type { ChapterStepProps } from "../../registry/types";
import "./FitAndLimit.css";

export default function FitAndLimit({ step }: ChapterStepProps) {
  if (step === 0) return (
    <section className="fl-scene fl-choice">
      <div className="fl-choice-copy"><span className="mono">CHOOSE BY DEVICE</span><h2>不是全功能复刻。<br />是<em>手机路线。</em></h2><p>同一套 OneBot 生态，不同的设备取舍。</p></div>
      <div className="fl-choice-map">
        <div className="fl-route-card fl-route-card--desktop"><span className="mono">DESKTOP</span><strong>NapCat</strong><div className="fl-desktop"><i /><b>PC QQ</b></div><small>功能更完整</small></div>
        <div className="fl-choice-or mono">OR</div>
        <div className="fl-route-card fl-route-card--mobile"><span className="mono">ANDROID</span><strong>PicoOnebot</strong><div className="fl-mobile"><i /><img src="/assets/pico-icon.png" alt="" /></div><small>免 Root · 轻量接入</small></div>
        <svg viewBox="0 0 960 650" aria-hidden="true"><path d="M40 320 H920"/><circle r="12"><animateMotion dur="2.2s" repeatCount="indefinite" path="M40 320 H920"/></circle></svg>
      </div>
    </section>
  );

  if (step === 1) return (
    <section className="fl-scene fl-boundary">
      <div className="fl-boundary-head"><span className="mono">CAPABILITY BOUNDARY</span><h2>先看你要的功能。</h2></div>
      <div className="fl-cap-grid">
        <div className="fl-cap-col fl-cap-col--yes"><div className="fl-cap-title"><span>✓</span><div><b>基础聊天可用</b><small className="mono">READY</small></div></div>{['私聊 / 群聊','图片与常用媒体','OneBot v11 事件'].map(x=><div className="fl-cap-item" key={x}><i />{x}</div>)}</div>
        <div className="fl-cap-col fl-cap-col--later"><div className="fl-cap-title"><span>…</span><div><b>群管理还不齐</b><small className="mono">LIMITED / VERIFY</small></div></div>{['群禁言 / 踢人','群名片','群公告'].map(x=><div className="fl-cap-item" key={x}><i />{x}</div>)}</div>
      </div>
      <div className="fl-fit-badge"><img src="/assets/pico-icon.png" alt="" /><div><span className="mono">BEST FIT</span><strong>旧 Android 手机 · 免 Root · 轻量接入</strong></div></div>
    </section>
  );

  if (step === 2) return (
    <section className="fl-scene fl-finale">
      <div className="fl-finale-glow" />
      <div className="fl-finale-route">
        <div className="fl-final-phone"><i /><img src="/assets/pico-icon.png" alt="" /><span className="mono">OLD ANDROID</span></div>
        <div className="fl-final-line"><i /><i /><i /><span className="mono">ONEBOT V11</span></div>
        <div className="fl-final-server"><i /><i /><i /><strong>AstrBot</strong><span className="mono">PC / SERVER</span></div>
      </div>
      <div className="fl-finale-copy"><span className="mono">NO ROOT · NO XPOSED</span><h1>让旧手机<br /><em>重新上岗。</em></h1><div className="fl-repo"><span className="mono">GITHUB</span><strong className="mono">Exmeaning / PicoOnebot</strong><small>安装包与对接文档</small></div></div>
      <div className="fl-final-stamp"><span>✦</span><b>PICO<br />ROUTE</b></div>
    </section>
  );
  return null;
}
