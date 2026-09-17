import { createContext, useContext, useEffect, useState, type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes } from "react";
import { X, Check, AlertTriangle, Info, Loader2, Sparkles } from "lucide-react";
import { cn } from "../utils/cn";

/* ---------------- Button ---------------- */
type BtnVariant = "primary" | "soft" | "ghost" | "danger" | "outline";
type BtnSize = "sm" | "md" | "lg" | "icon";

export function Button({
  className,
  variant = "primary",
  size = "md",
  loading,
  children,
  disabled,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: BtnVariant; size?: BtnSize; loading?: boolean }) {
  const variants: Record<BtnVariant, string> = {
    primary:
      "bg-gradient-to-br from-pico to-pico-deep text-white shadow-cute hover:brightness-105",
    soft: "bg-pico-soft text-pico-deep hover:bg-pico/25",
    ghost: "text-pico-muted hover:bg-pico-soft hover:text-pico-deep",
    danger: "bg-rose-100 text-rose-600 hover:bg-rose-200",
    outline: "border-2 border-pico-line bg-white/70 text-pico-deep hover:border-pico/60 hover:bg-pico-softer",
  };
  const sizes: Record<BtnSize, string> = {
    sm: "h-8 px-3 text-xs gap-1.5 rounded-xl",
    md: "h-10 px-4 text-sm gap-2 rounded-2xl",
    lg: "h-12 px-6 text-base gap-2 rounded-2xl",
    icon: "h-9 w-9 rounded-xl",
  };
  return (
    <button
      className={cn(
        "pico-ring inline-flex select-none items-center justify-center font-bold disabled:pointer-events-none disabled:opacity-50",
        variants[variant],
        sizes[size],
        className
      )}
      disabled={disabled || loading}
      {...rest}
    >
      {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
      {children}
    </button>
  );
}

/* ---------------- Card ---------------- */
export function Card({ className, children, decorated }: { className?: string; children: ReactNode; decorated?: boolean }) {
  return (
    <div
      className={cn(
        "pico-glass relative overflow-hidden rounded-bubble border border-white/80 shadow-cute ring-1 ring-pico-line",
        className
      )}
    >
      {decorated ? (
        <>
          <span className="pointer-events-none absolute -right-8 -top-8 h-28 w-28 rounded-full bg-pico/15 blur-2xl" />
          <span className="pointer-events-none absolute -bottom-10 -left-6 h-24 w-24 rounded-full bg-pico/10 blur-2xl" />
        </>
      ) : null}
      {children}
    </div>
  );
}

export function CardHeader({ title, desc, action, icon }: { title: ReactNode; desc?: ReactNode; action?: ReactNode; icon?: ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-4 px-4 pt-4 sm:px-6 sm:pt-6">
      <div className="flex items-start gap-3">
        {icon ? <div className="mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-2xl bg-pico-soft text-pico-deep">{icon}</div> : null}
        <div>
          <h3 className="text-base font-extrabold tracking-tight text-pico-ink">{title}</h3>
          {desc ? <p className="mt-0.5 text-xs text-pico-muted">{desc}</p> : null}
        </div>
      </div>
      {action}
    </div>
  );
}

/* ---------------- Inputs ---------------- */
export function Input({ className, ...rest }: InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input
      className={cn(
        "pico-ring h-10 w-full rounded-2xl border-2 border-pico-line bg-white/80 px-4 text-sm text-pico-ink placeholder:text-pico-muted/60 transition focus:border-pico/70",
        className
      )}
      {...rest}
    />
  );
}

export function Select({ className, children, ...rest }: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <div className="relative">
      <select
        className={cn(
          "pico-ring h-10 w-full appearance-none rounded-2xl border-2 border-pico-line bg-white/80 px-4 pr-10 text-sm text-pico-ink transition focus:border-pico/70",
          className
        )}
        {...rest}
      >
        {children}
      </select>
      <svg className="pointer-events-none absolute right-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-pico-muted" viewBox="0 0 20 20" fill="none">
        <path d="M6 8l4 4 4-4" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    </div>
  );
}

export function Field({ label, hint, children, className }: { label: ReactNode; hint?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <label className={cn("block", className)}>
      <span className="mb-1.5 block text-xs font-bold text-pico-ink/80">{label}</span>
      {children}
      {hint ? <span className="mt-1 block text-[11px] text-pico-muted">{hint}</span> : null}
    </label>
  );
}

export function Toggle({ checked, onChange, label, desc, disabled }: { checked: boolean; onChange: (v: boolean) => void; label?: ReactNode; desc?: ReactNode; disabled?: boolean }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={cn("pico-ring group flex w-full items-center justify-between gap-4 rounded-2xl text-left disabled:opacity-50", label ? "px-1 py-1.5" : "")}
    >
      {label ? (
        <span>
          <span className="block text-sm font-bold text-pico-ink">{label}</span>
          {desc ? <span className="block text-[11px] text-pico-muted">{desc}</span> : null}
        </span>
      ) : null}
      <span
        className={cn(
          "relative inline-flex h-7 w-12 shrink-0 items-center rounded-full transition-colors duration-300",
          checked ? "bg-gradient-to-r from-pico to-pico-deep" : "bg-pico-line"
        )}
      >
        <span
          className={cn(
            "absolute left-1 h-5 w-5 rounded-full bg-white shadow transition-transform duration-300",
            checked ? "translate-x-5" : "translate-x-0"
          )}
        >
          <span className={cn("absolute inset-0 m-auto h-1.5 w-1.5 rounded-full transition", checked ? "bg-pico" : "bg-pico-line")} />
        </span>
      </span>
    </button>
  );
}

/* ---------------- Badge ---------------- */
export function Badge({ children, tone = "pink", className }: { children: ReactNode; tone?: "pink" | "green" | "amber" | "red" | "gray" | "blue"; className?: string }) {
  const tones = {
    pink: "bg-pico-soft text-pico-deep",
    green: "bg-emerald-100 text-emerald-700",
    amber: "bg-amber-100 text-amber-700",
    red: "bg-rose-100 text-rose-600",
    gray: "bg-slate-100 text-slate-500",
    blue: "bg-sky-100 text-sky-700",
  };
  return <span className={cn("inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-[11px] font-extrabold", tones[tone], className)}>{children}</span>;
}

/* ---------------- Modal ---------------- */
export function Modal({ open, onClose, title, children, footer, wide }: { open: boolean; onClose: () => void; title: ReactNode; children: ReactNode; footer?: ReactNode; wide?: boolean }) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    document.body.style.overflow = "hidden";
    return () => {
      window.removeEventListener("keydown", onKey);
      document.body.style.overflow = "";
    };
  }, [open, onClose]);
  if (!open) return null;
  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center p-4 sm:items-center" role="dialog" aria-modal>
      <div className="absolute inset-0 bg-pico-deep/25 backdrop-blur-sm" onClick={onClose} />
      <div className={cn("pico-glass relative w-full rounded-bubble border border-white shadow-cute-lg ring-1 ring-pico-line", wide ? "max-w-3xl" : "max-w-lg")}>
        <div className="flex items-center justify-between border-b border-pico-line/70 px-6 py-4">
          <h3 className="text-base font-extrabold text-pico-ink">{title}</h3>
          <Button variant="ghost" size="icon" onClick={onClose} aria-label="关闭">
            <X className="h-4 w-4" />
          </Button>
        </div>
        <div className="max-h-[70vh] overflow-y-auto px-6 py-5">{children}</div>
        {footer ? <div className="flex items-center justify-end gap-2 border-t border-pico-line/70 px-6 py-4">{footer}</div> : null}
      </div>
    </div>
  );
}

/* ---------------- Toast ---------------- */
type Toast = { id: number; kind: "success" | "error" | "info"; text: string };
const ToastCtx = createContext<{ push: (kind: Toast["kind"], text: string) => void } | null>(null);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [list, setList] = useState<Toast[]>([]);
  const push = (kind: Toast["kind"], text: string) => {
    const id = Date.now() + Math.random();
    setList((l) => [...l, { id, kind, text }]);
    setTimeout(() => setList((l) => l.filter((t) => t.id !== id)), 3200);
  };
  return (
    <ToastCtx.Provider value={{ push }}>
      {children}
      <div className="pointer-events-none fixed inset-x-0 top-4 z-[60] flex flex-col items-center gap-2 px-4">
        {list.map((t) => (
          <div
            key={t.id}
            className={cn(
              "pico-glass flex items-center gap-2 rounded-full border border-white px-4 py-2 text-sm font-bold shadow-cute ring-1",
              t.kind === "success" && "text-emerald-700 ring-emerald-200",
              t.kind === "error" && "text-rose-600 ring-rose-200",
              t.kind === "info" && "text-pico-deep ring-pico-line"
            )}
          >
            {t.kind === "success" ? <Check className="h-4 w-4" /> : t.kind === "error" ? <AlertTriangle className="h-4 w-4" /> : <Info className="h-4 w-4" />}
            {t.text}
          </div>
        ))}
      </div>
    </ToastCtx.Provider>
  );
}

export function useToast() {
  const ctx = useContext(ToastCtx);
  if (!ctx) throw new Error("useToast must be inside ToastProvider");
  return ctx;
}

/* ---------------- Misc ---------------- */
export function PageHeader({ title, desc, action }: { title: ReactNode; desc?: ReactNode; action?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 className="flex items-center gap-2 text-2xl font-black tracking-tight text-pico-ink">
          {title}
          <Sparkles className="h-5 w-5 text-pico" />
        </h1>
        {desc ? <p className="mt-1 text-sm text-pico-muted">{desc}</p> : null}
      </div>
      {action}
    </div>
  );
}

export function Empty({ title, desc, action }: { title: string; desc?: string; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center rounded-3xl border-2 border-dashed border-pico-line px-6 py-12 text-center">
      <div className="mb-3 flex h-14 w-14 items-center justify-center rounded-full bg-pico-soft">
        <HeartDot className="h-7 w-7 text-pico" />
      </div>
      <p className="text-sm font-extrabold text-pico-ink">{title}</p>
      {desc ? <p className="mt-1 max-w-xs text-xs text-pico-muted">{desc}</p> : null}
      {action ? <div className="mt-4">{action}</div> : null}
    </div>
  );
}

export function HeartDot({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" className={className} fill="currentColor" aria-hidden>
      <path d="M12 21s-7.5-4.6-9.6-9.2C.9 8.4 3 5 6.4 5c2 0 3.4 1.1 4.1 2.3h1C12.2 6.1 13.6 5 15.6 5 19 5 21.1 8.4 19.6 11.8 17.5 16.4 12 21 12 21z" />
    </svg>
  );
}

export function Stat({ label, value, sub, icon }: { label: string; value: ReactNode; sub?: ReactNode; icon: ReactNode }) {
  return (
    <Card className="p-5" decorated>
      <div className="flex items-start justify-between">
        <div>
          <p className="text-xs font-bold text-pico-muted">{label}</p>
          <p className="mt-1 text-2xl font-black tracking-tight text-pico-ink">{value}</p>
          {sub ? <p className="mt-1 text-[11px] text-pico-muted">{sub}</p> : null}
        </div>
        <div className="flex h-11 w-11 items-center justify-center rounded-2xl bg-gradient-to-br from-pico to-pico-deep text-white shadow-cute">{icon}</div>
      </div>
    </Card>
  );
}

export function Kbd({ children }: { children: ReactNode }) {
  return <kbd className="rounded-lg border border-pico-line bg-white px-1.5 py-0.5 font-mono text-[10px] font-bold text-pico-muted">{children}</kbd>;
}
