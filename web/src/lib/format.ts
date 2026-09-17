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
