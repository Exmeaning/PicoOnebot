import { Component, type ErrorInfo, type ReactNode } from "react";
import { AlertTriangle, RefreshCw } from "lucide-react";

type Props = {
  children: ReactNode;
};

type State = {
  error: Error | null;
};

export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error("PicoOnebot WebUI uncaught error:", error, info.componentStack);
  }

  private handleReload = () => {
    window.location.reload();
  };

  private handleRetry = () => {
    this.setState({ error: null });
  };

  render() {
    if (!this.state.error) return this.props.children;
    return (
      <div className="flex min-h-full items-center justify-center p-6">
        <div className="pico-glass w-full max-w-md rounded-bubble border border-white p-8 text-center shadow-cute-lg ring-1 ring-pico-line">
          <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-2xl bg-rose-100 text-rose-600">
            <AlertTriangle className="h-6 w-6" />
          </div>
          <h1 className="mt-4 text-xl font-black text-pico-ink">页面遇到了一点问题</h1>
          <p className="mt-2 break-words text-xs leading-relaxed text-pico-muted">
            {this.state.error.message || "发生未知渲染错误，请尝试刷新页面。"}
          </p>
          <div className="mt-6 flex items-center justify-center gap-2">
            <button
              type="button"
              onClick={this.handleRetry}
              className="pico-ring inline-flex h-10 items-center justify-center rounded-2xl bg-pico-soft px-4 text-sm font-bold text-pico-deep hover:bg-pico/25"
            >
              重试
            </button>
            <button
              type="button"
              onClick={this.handleReload}
              className="pico-ring inline-flex h-10 items-center justify-center gap-2 rounded-2xl bg-gradient-to-br from-pico to-pico-deep px-4 text-sm font-bold text-white shadow-cute hover:brightness-105"
            >
              <RefreshCw className="h-4 w-4" /> 刷新页面
            </button>
          </div>
        </div>
      </div>
    );
  }
}
