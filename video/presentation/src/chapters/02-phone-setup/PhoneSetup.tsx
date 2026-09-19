import type { ChapterStepProps } from "../../registry/types";
import "./PhoneSetup.css";

function MiniPhone({ label = "ANDROID" }: { label?: string }) {
  return (
    <div className="ps-phone" aria-hidden="true">
      <i />
      <div className="ps-phone-screen">
        <img src="/assets/pico-icon.png" alt="" />
        <span className="mono">{label}</span>
      </div>
    </div>
  );
}

export default function PhoneSetup({ step }: ChapterStepProps) {
  if (step === 0) {
    return (
      <section className="ps-scene ps-release">
        <div className="ps-release-copy">
          <p className="mono">STEP 01 · GET THE APK</p>
          <h2>先装上<br /><em>PicoOnebot</em></h2>
          <div className="ps-release-url mono">github.com/Exmeaning/PicoOnebot/releases</div>
        </div>
        <div className="ps-release-pipeline">
          <div className="ps-repo-window">
            <div className="ps-repo-top"><span /><span /><span /><b className="mono">RELEASES</b></div>
            <div className="ps-release-row">
              <div className="ps-file-icon mono">APK</div>
              <div><strong>PicoOnebot_0.13.apk</strong><small>Latest release · Android package</small></div>
              <span className="ps-download mono">DOWNLOAD</span>
            </div>
          </div>
          <svg className="ps-transfer" viewBox="0 0 430 150" aria-hidden="true"><path d="M20 75 H355"/><path d="m340 48 42 27-42 27"/><circle r="9"><animateMotion dur="1.8s" repeatCount="indefinite" path="M20 75 H355"/></circle></svg>
          <div className="ps-install-target">
            <MiniPhone />
            <div className="ps-install-progress"><i /><span className="mono">INSTALLING</span></div>
          </div>
        </div>
      </section>
    );
  }

  if (step === 1) {
    return (
      <section className="ps-scene ps-abi">
        <div className="ps-abi-hero">
          <span className="mono">REQUIRED ABI</span>
          <strong className="hero-num">32</strong>
          <b>位应用支持</b>
        </div>
        <div className="ps-abi-gate">
          <div className="ps-chip ps-chip--pass">
            <span className="mono">armeabi-v7a</span><strong>可以安装</strong>
          </div>
          <div className="ps-gate-core"><i /><span className="mono">WATCH QQ NATIVE LIB</span></div>
          <div className="ps-chip ps-chip--stop">
            <span className="mono">arm64-only</span><strong>可能装不上</strong>
          </div>
        </div>
        <div className="ps-abi-track"><i /><i /><i /><i /></div>
      </section>
    );
  }

  if (step === 2) {
    return (
      <section className="ps-scene ps-platforms">
        <div className="ps-platform-title"><span className="mono">MORE WAYS TO RUN</span><h2>不只旧手机。<br /><em>三条运行路线。</em></h2></div>
        <div className="ps-platform-grid">
          <div className="ps-platform-card ps-platform-card--phone"><span className="mono">ANDROID</span><strong>真机 APK</strong><div className="ps-device-icon"><i /></div><small>最轻量 · 推荐</small><div className="ps-load"><b /></div></div>
          <div className="ps-platform-card"><span className="mono">WINDOWS / MACOS</span><strong>安卓模拟器</strong><div className="ps-window-icon"><i /><i /><i /><b>ANDROID</b></div><small>没有旧手机也能跑</small><div className="ps-load ps-load--mid"><b /></div></div>
          <div className="ps-platform-card"><span className="mono">LINUX / SERVER</span><strong>Docker</strong><div className="ps-docker-icon"><i>ANDROID</i><b>CONTAINER</b></div><small>内置安卓环境 · 开销更大</small><div className="ps-load ps-load--high"><b /></div></div>
        </div>
        <div className="ps-platform-note"><span className="mono">RESOURCE COST</span><b>低</b><i /><b>高</b></div>
      </section>
    );
  }

  if (step === 3) {
    return (
      <section className="ps-scene ps-lan">
        <div className="ps-lan-copy">
          <p className="mono">PHONE READY</p>
          <h2>给权限。<br />手机里<em>直接打开</em>。</h2>
          <div className="ps-permissions">
            <span>基础运行权限</span><i className="ps-toggle"><b /></i><strong>ON</strong>
          </div>
        </div>
        <div className="ps-lan-map ps-local-map">
          <div className="ps-wifi-ring ps-wifi-ring--one" />
          <div className="ps-wifi-ring ps-wifi-ring--two" />
          <div className="ps-phone-node"><MiniPhone label="127.0.0.1" /><span className="mono">PHONE / PICOONEBOT</span></div>
          <div className="ps-router-node ps-local-node"><i /><i /><strong className="mono">LOCALHOST</strong><span>同一台手机</span></div>
          <div className="ps-browser-node"><b className="mono">BROWSER</b><span>127.0.0.1:6099</span></div>
          <div className="ps-lan-option"><span>也可从其它设备访问</span><strong>手机 IP : 6099</strong></div>
          <svg viewBox="0 0 940 600" aria-hidden="true"><path d="M200 300 C360 300 350 150 500 150 S680 300 790 300"/><path d="M500 150 V450 H790"/></svg>
        </div>
      </section>
    );
  }

  if (step === 4) {
    return (
      <section className="ps-scene ps-login-shot">
        <div className="ps-browser-frame">
          <div className="ps-browser-bar"><i /><i /><i /><span className="mono">http://127.0.0.1:6099</span></div>
          <img src="/assets/webui-login.png" alt="PicoOnebot WebUI 登录页" />
        </div>
        <div className="ps-login-callout">
          <span className="mono">WEBUI PORT</span>
          <strong className="hero-num">6099</strong>
          <p>初始密码</p>
          <b className="mono">picopico</b>
          <small>首次登录后修改</small>
        </div>
      </section>
    );
  }

  if (step === 5) {
    return (
      <section className="ps-scene ps-qr-shot">
        <div className="ps-qr-browser">
          <img src="/assets/webui-qr.png" alt="PicoOnebot 扫码登录页" />
          <div className="ps-qr-focus" />
        </div>
        <div className="ps-qr-copy">
          <p className="mono">QQ LOGIN</p>
          <h2>最后一步：<br /><em>扫码上线</em></h2>
          <div className="ps-qr-flow"><span>另一台手机 QQ</span><b>→</b><span>确认登录</span></div>
        </div>
      </section>
    );
  }

  return null;
}
