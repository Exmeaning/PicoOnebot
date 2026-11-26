import { useState, type FormEvent } from "react";
import { ArrowLeft, Eye, EyeOff, KeyRound, LockKeyhole, ShieldCheck } from "lucide-react";
import { Logo } from "../components/Logo";
import { Button, Field, Input, useToast } from "../components/ui";
import { ApiError, api, setToken } from "../lib/api";

const MIN_PASSWORD_LENGTH = 8;

export function ChangePassword({
  onSuccess,
  onCancel,
}: {
  onSuccess: () => void;
  onCancel: () => void;
}) {
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [show, setShow] = useState(false);
  const [loading, setLoading] = useState(false);
  const toast = useToast();

  const hasEdgeWhitespace = password !== password.trim();
  const validationMessage = hasEdgeWhitespace
    ? "密码首尾不能包含空格"
    : password.length < MIN_PASSWORD_LENGTH
      ? `控制台密码至少 ${MIN_PASSWORD_LENGTH} 位，当前为 ${password.length} 位`
      : password === "picopico"
        ? "不能继续使用初始密码 picopico"
        : password !== confirm
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
      const result = await api.initializeWebuiPassword(password);
      if (!result.ok) throw new Error(result.message || "修改失败");
      if (result.token) setToken(result.token);
      toast.push("success", "控制台密码已更新");
      onSuccess();
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        // Central unauthorized handler resets back to Login and notifies the user.
        return;
      }
      toast.push("error", (error as Error).message || "修改失败");
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
              hasEdgeWhitespace
                ? "密码首尾不能包含空格"
                : password.length > 0 && password.length < MIN_PASSWORD_LENGTH
                  ? `已输入 ${password.length} 位，还需 ${MIN_PASSWORD_LENGTH - password.length} 位`
                  : "至少 8 位，首尾不能含空格，且不能与初始密码相同"
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
          <Button type="button" variant="ghost" className="w-full" onClick={onCancel} disabled={loading}>
            <ArrowLeft className="h-4 w-4" /> 返回登录
          </Button>
        </div>
      </form>
    </div>
  );
}
