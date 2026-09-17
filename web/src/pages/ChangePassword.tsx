import { useState, type FormEvent } from "react";
import { Eye, EyeOff, KeyRound, LockKeyhole, ShieldCheck } from "lucide-react";
import { Logo } from "../components/Logo";
import { Button, Field, Input, useToast } from "../components/ui";
import { api } from "../lib/api";

const MIN_PASSWORD_LENGTH = 8;

export function ChangePassword({ onSuccess }: { onSuccess: () => void }) {
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [show, setShow] = useState(false);
  const [loading, setLoading] = useState(false);
  const toast = useToast();
  const candidate = password.trim();
  const validationMessage =
    candidate.length < MIN_PASSWORD_LENGTH
      ? `控制台密码至少 ${MIN_PASSWORD_LENGTH} 位，当前为 ${candidate.length} 位`
      : candidate === "picopico"
        ? "不能继续使用初始密码 picopico"
        : candidate !== confirm.trim()
          ? "两次输入的密码不一致"
          : "";

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (validationMessage) {
      toast.push("error", validationMessage);
      return;
    }
    setLoading(true);
    try {
      const result = await api.initializeWebuiPassword(candidate);
      if (!result.ok) throw new Error(result.message || "修改失败");
      toast.push("success", "控制台密码已更新");
      onSuccess();
    } catch (error) {
      toast.push("error", (error as Error).message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="relative flex min-h-full items-center justify-center overflow-hidden px-4 py-10">
      <div className="pointer-events-none absolute inset-0 pico-dots opacity-60" />
      <form onSubmit={submit} className="pico-glass relative w-full max-w-md rounded-bubble border border-white p-8 shadow-cute-lg ring-1 ring-pico-line">
        <Logo className="h-9" />
        <div className="mt-7 flex h-12 w-12 items-center justify-center rounded-2xl bg-pico-soft text-pico-deep">
          <ShieldCheck className="h-6 w-6" />
        </div>
        <h1 className="mt-4 text-2xl font-black text-pico-ink">设置新的控制台密码</h1>
        <p className="mt-2 text-sm leading-relaxed text-pico-muted">
          当前仍在使用初始密码。完成修改后才能进入控制台。
        </p>

        <div className="mt-6 space-y-4">
          <Field
            label="新密码"
            hint={
              candidate.length > 0 && candidate.length < MIN_PASSWORD_LENGTH
                ? `已输入 ${candidate.length} 位，还需 ${MIN_PASSWORD_LENGTH - candidate.length} 位`
                : "至少 8 位，不能与初始密码相同"
            }
          >
            <div className="relative">
              <KeyRound className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-pico-muted" />
              <Input
                className="pl-10 pr-11"
                type={show ? "text" : "password"}
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                autoFocus
                autoComplete="new-password"
              />
              <button
                type="button"
                onClick={() => setShow((value) => !value)}
                className="absolute right-2 top-1/2 flex h-7 w-7 -translate-y-1/2 items-center justify-center rounded-lg text-pico-muted hover:bg-pico-soft"
                aria-label={show ? "隐藏密码" : "显示密码"}
              >
                {show ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </button>
            </div>
          </Field>
          <Field label="确认新密码" hint={confirm && confirm !== password ? "两次输入不一致" : undefined}>
            <div className="relative">
              <LockKeyhole className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-pico-muted" />
              <Input
                className="pl-10"
                type={show ? "text" : "password"}
                value={confirm}
                onChange={(event) => setConfirm(event.target.value)}
                autoComplete="new-password"
              />
            </div>
          </Field>
          <Button type="submit" size="lg" className="w-full" loading={loading}>
            保存并进入控制台
          </Button>
        </div>
      </form>
    </div>
  );
}
