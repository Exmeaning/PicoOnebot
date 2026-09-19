import type { AppliedResult } from "./types";

export function fmtDuration(sec: number, short = false) {
  const d = Math.floor(sec / 86400);
  const h = Math.floor((sec % 86400) / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = Math.floor(sec % 60);
  if (short) {
    if (d > 0) return `${d}天${h}时`;
    if (h > 0) return `${h}时${m}分`;
    return `${m}分${s}秒`;
  }
  const parts: string[] = [];
  if (d) parts.push(`${d} 天`);
  if (h) parts.push(`${h} 小时`);
  if (m) parts.push(`${m} 分钟`);
  if (!parts.length) parts.push(`${s} 秒`);
  return parts.join(" ");
}

export function fmtNumber(n: number) {
  return n.toLocaleString("zh-CN");
}

export function fmtTime(ts: number) {
  const d = new Date(ts);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

export function fmtDateTime(ts: number) {
  const d = new Date(ts);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${fmtTime(ts)}`;
}

export function greeting() {
  const h = new Date().getHours();
  if (h < 5) return "夜深了";
  if (h < 11) return "早上好";
  if (h < 14) return "中午好";
  if (h < 18) return "下午好";
  return "晚上好";
}

/**
 * 把后端回报的「实际做了什么」翻成一句提示语。
 *
 * 这里只描述后端确认发生过的事:重载了几个监听、起了几条反向连接。
 * 不许写「已生效」这种后端没保证过的话 —— 反向连接是异步握手的,
 * 保存的那一刻它还没连上,说「已连接」就是骗人。
 */
export function fmtApplied(result: { restartRequired?: boolean; warnings?: string[]; applied?: AppliedResult }): {
  tone: "success" | "info" | "error";
  message: string;
} {
  const applied = result.applied;
  if (result.warnings?.length) {
    return { tone: "error", message: "配置已写入，但部分监听没能启动：" + result.warnings.join("；") };
  }
  if (result.restartRequired) {
    return { tone: "info", message: "配置已保存，这部分设置要重启 QQ 才会生效" };
  }
  if (!applied?.reloaded) {
    return { tone: "success", message: "配置已保存（网络部分没有改动，无需重载）" };
  }
  const parts: string[] = [];
  if (applied.listeners > 0) parts.push(`${applied.listeners} 个监听已重启`);
  if (applied.reverse > 0) parts.push(`${applied.reverse} 条反向连接已开始握手`);
  if (applied.reporters > 0) parts.push(`${applied.reporters} 个上报地址已入队`);
  if (parts.length === 0) {
    return { tone: "info", message: "配置已保存并重载：当前没有任何启用的连接在跑" };
  }
  return { tone: "success", message: "配置已保存并重载：" + parts.join("、") + "。实际状态见「概览」的连接列表" };
}
