import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";

export type ThemePreset = {
  id: string;
  name: string;
  color: string;
  ink: string;
  muted: string;
  bgA: string;
  bgB: string;
};

export const THEME_PRESETS: ThemePreset[] = [
  { id: "sakura", name: "樱花粉", color: "#ff85a2", ink: "#5b3a47", muted: "#a67c8c", bgA: "#fff5f8", bgB: "#ffe9f0" },
  { id: "peach", name: "蜜桃橘", color: "#ff9a8b", ink: "#5c3d36", muted: "#a98079", bgA: "#fff6f2", bgB: "#ffeae3" },
  { id: "lavender", name: "薰衣草", color: "#b58cff", ink: "#463a5b", muted: "#8c7ca6", bgA: "#f8f5ff", bgB: "#efe9ff" },
  { id: "mint", name: "薄荷糖", color: "#5fd3b3", ink: "#2f4f47", muted: "#6f9a8f", bgA: "#f2fcf8", bgB: "#e4f7f0" },
  { id: "sky", name: "天空蓝", color: "#7ab8ff", ink: "#33465c", muted: "#7c92aa", bgA: "#f3f8ff", bgB: "#e6f0ff" },
  { id: "lemon", name: "柠檬黄", color: "#f2b64b", ink: "#5a4520", muted: "#a68c5a", bgA: "#fffaf0", bgB: "#fff2d9" },
  { id: "candy", name: "糖果紫", color: "#f07bd1", ink: "#573a52", muted: "#a37c9c", bgA: "#fff4fc", bgB: "#fde6f7" },
];

type ThemeState = {
  presetId: string;
  customColor: string | null;
  color: string;
  setPreset: (id: string) => void;
  setCustomColor: (hex: string) => void;
};

const ThemeCtx = createContext<ThemeState | null>(null);
const KEY = "picoonebot.theme";

function hexToRgb(hex: string) {
  const h = hex.replace("#", "");
  const full = h.length === 3 ? h.split("").map((c) => c + c).join("") : h;
  const n = parseInt(full, 16);
  return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255 };
}
function mix(hex: string, target: { r: number; g: number; b: number }, amount: number) {
  const c = hexToRgb(hex);
  const r = Math.round(c.r + (target.r - c.r) * amount);
  const g = Math.round(c.g + (target.g - c.g) * amount);
  const b = Math.round(c.b + (target.b - c.b) * amount);
  return `#${[r, g, b].map((v) => v.toString(16).padStart(2, "0")).join("")}`;
}

function derive(color: string): Omit<ThemePreset, "id" | "name"> {
  const white = { r: 255, g: 255, b: 255 };
  const dark = { r: 50, g: 30, b: 40 };
  return {
    color,
    ink: mix(color, dark, 0.72),
    muted: mix(color, { r: 120, g: 100, b: 110 }, 0.55),
    bgA: mix(color, white, 0.94),
    bgB: mix(color, white, 0.88),
  };
}

function applyTheme(t: Omit<ThemePreset, "id" | "name">) {
  const root = document.documentElement;
  root.style.setProperty("--pico", t.color);
  root.style.setProperty("--pico-ink", t.ink);
  root.style.setProperty("--pico-muted", t.muted);
  root.style.setProperty("--pico-bg-a", t.bgA);
  root.style.setProperty("--pico-bg-b", t.bgB);
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute("content", t.color);
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [presetId, setPresetId] = useState("sakura");
  const [customColor, setCustom] = useState<string | null>(null);

  useEffect(() => {
    try {
      const raw = localStorage.getItem(KEY);
      if (raw) {
        const s = JSON.parse(raw);
        if (s.presetId) setPresetId(s.presetId);
        if (s.customColor) setCustom(s.customColor);
      }
    } catch {
      /* ignore */
    }
  }, []);

  useEffect(() => {
    const preset = THEME_PRESETS.find((p) => p.id === presetId) ?? THEME_PRESETS[0];
    applyTheme(customColor ? derive(customColor) : preset);
    localStorage.setItem(KEY, JSON.stringify({ presetId, customColor }));
  }, [presetId, customColor]);

  const value = useMemo<ThemeState>(
    () => ({
      presetId,
      customColor,
      color: customColor ?? (THEME_PRESETS.find((p) => p.id === presetId)?.color ?? "#ff85a2"),
      setPreset: (id) => {
        setCustom(null);
        setPresetId(id);
      },
      setCustomColor: (hex) => setCustom(hex),
    }),
    [presetId, customColor]
  );

  return <ThemeCtx.Provider value={value}>{children}</ThemeCtx.Provider>;
}

export function useTheme() {
  const ctx = useContext(ThemeCtx);
  if (!ctx) throw new Error("useTheme must be used inside ThemeProvider");
  return ctx;
}
