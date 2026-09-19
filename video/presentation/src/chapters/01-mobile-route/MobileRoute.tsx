import type { ChapterStepProps } from "../../registry/types";
import "./MobileRoute.css";

function PhoneFrame({ compact = false }: { compact?: boolean }) {
  return (
    <div className={`mr-phone${compact ? " mr-phone--compact" : ""}`} aria-hidden="true">
      <div className="mr-phone-speaker" />
      <div className="mr-phone-screen">
        <div className="mr-phone-grid" />
        <div className="mr-phone-scan" />
        <span className="mr-phone-os mono">ANDROID</span>
        <span className="mr-phone-port hero-num">6099</span>
        <span className="mr-phone-state mono"><i /> DEVICE ONLINE</span>
      </div>
    </div>
  );
}

function RouteArrow() {
  return (
    <svg className="mr-route-arrow" viewBox="0 0 520 140" aria-hidden="true">
      <path className="mr-route-track" d="M18 72 H162 C214 72 218 24 274 24 H465" />
      <path className="mr-route-track mr-route-track--ghost" d="M162 72 C214 72 218 120 274 120 H420" />
      <path className="mr-route-tip" d="m455 10 38 14-38 14" />
      <circle className="mr-route-pulse" r="8">
        <animateMotion dur="2.2s" repeatCount="indefinite" path="M18 72 H162 C214 72 218 24 274 24 H465" />
      </circle>
    </svg>
  );
}

export default function MobileRoute({ step }: ChapterStepProps) {
  if (step === 0) {
    return (
      <section className="mr-scene mr-hook">
        <div className="mr-hook-device">
          <PhoneFrame />
          <div className="mr-hook-node mono">ASTRBOT</div>
          <RouteArrow />
        </div>
        <div className="mr-hook-copy">
          <p className="mr-eyebrow mono">ANDROID / ONEBOT V11 / ASTRBOT</p>
          <h1>免 Root<br /><em>手机端</em>挂 AstrBot</h1>
          <div className="mr-hook-meta">
            <span>旧设备再利用</span>
            <b>→</b>
            <span>QQ 接入</span>
          </div>
        </div>
      </section>
    );
  }

  if (step === 1) {
    return (
      <section className="mr-scene mr-barriers">
        <div className="mr-barrier-word mr-barrier-word--left">
          <span className="mono">ROOT</span>
          <i />
        </div>
        <div className="mr-pico-mark" aria-label="PicoOnebot logo">
          <div className="mr-logo-mask" />
          <strong>PicoOnebot</strong>
          <small className="mono">ANDROID NATIVE ONEBOT</small>
        </div>
        <div className="mr-barrier-word mr-barrier-word--right">
          <span className="mono">XPOSED</span>
          <i />
        </div>
        <p className="mr-barrier-copy">两道门槛，<em>都不用。</em></p>
        <svg className="mr-bypass" viewBox="0 0 1500 300" aria-hidden="true">
          <path d="M70 150 H430 C510 150 510 42 600 42 H900 C990 42 990 150 1070 150 H1430" />
        </svg>
      </section>
    );
  }

  if (step === 2) {
    return (
      <section className="mr-scene mr-stack-scene">
        <div className="mr-stack-title">
          <p className="mono">INSIDE THE ANDROID PROCESS</p>
          <h2>装进手表 QQ，<br />吐出 <em>OneBot v11</em></h2>
        </div>
        <div className="mr-process-shell">
          <div className="mr-process-label mono">ANDROID · WATCH QQ PROCESS</div>
          <div className="mr-process-layer mr-process-layer--watch">
            <span className="mono">WATCH QQ</span>
            <strong>官方轻量客户端</strong>
          </div>
          <div className="mr-process-layer mr-process-layer--pico">
            <span className="mono">INJECTED LAYER</span>
            <strong>PicoOnebot</strong>
            <div className="mr-logo-mask mr-logo-mask--small" />
          </div>
          <div className="mr-packet mr-packet--one mono">NT</div>
          <div className="mr-packet mr-packet--two mono">EVENT</div>
        </div>
        <svg className="mr-process-output" viewBox="0 0 470 220" aria-hidden="true">
          <path d="M20 110 H315" />
          <path d="m300 82 44 28-44 28" />
        </svg>
        <div className="mr-output-node">
          <span className="mono">STANDARD OUTPUT</span>
          <strong className="hero-num">ONEBOT<br />V11</strong>
        </div>
      </section>
    );
  }

  if (step === 3) {
    return (
      <section className="mr-scene mr-compare">
        <div className="mr-compare-copy">
          <p className="mono">TWO ROUTES / ONE ECOSYSTEM</p>
          <h2>不是完整复刻。<br />是换一条<em>设备路线。</em></h2>
        </div>
      <div className="mr-rail-map">
        <div className="mr-rail-label mr-rail-label--desktop">
          <span className="mono">DESKTOP ROUTE</span>
          <strong>NapCat</strong>
          <small>功能更全 · 更适合桌面端</small>
        </div>
        <div className="mr-rail mr-rail--desktop"><i /><i /><i /></div>
        <div className="mr-rail-junction mono">ONEBOT V11</div>
        <div className="mr-rail mr-rail--mobile"><i /><i /><i /></div>
        <div className="mr-rail-label mr-rail-label--mobile">
          <span className="mono">MOBILE ROUTE</span>
          <strong>PicoOnebot</strong>
          <small>免 Root · 旧 Android 手机</small>
        </div>
        <div className="mr-rail-tracer" />
      </div>
      <div className="mr-route-verdict">
        <PhoneFrame compact />
        <div>
          <span className="mono">CHOSEN PATH</span>
          <strong>手机路线</strong>
        </div>
      </div>
      </section>
    );
  }

  return null;
}
