import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  AlertTriangle,
  Braces,
  ChevronRight,
  Copy,
  FileCode2,
  FileText,
  Folder,
  FolderOpen,
  HardDrive,
  Home,
  RefreshCw,
  RotateCcw,
  Save,
  Search,
} from "lucide-react";
import { Badge, Button, Card, CardHeader, Empty, Input, PageHeader, useToast } from "../components/ui";
import { api } from "../lib/api";
import { fmtApplied, fmtDateTime } from "../lib/format";
import type { FileDocument, FileEntry, FileListing } from "../lib/types";
import { cn } from "../utils/cn";

function formatSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MiB`;
}

function fileIcon(entry: FileEntry) {
  if (entry.type === "directory") return <Folder className="h-4 w-4" />;
  if (entry.name.toLowerCase().endsWith(".json")) return <FileCode2 className="h-4 w-4" />;
  return <FileText className="h-4 w-4" />;
}

export function FileManager() {
  const toast = useToast();
  const requestId = useRef(0);
  const directoryRequestId = useRef(0);
  const [listing, setListing] = useState<FileListing | null>(null);
  const [selected, setSelected] = useState<FileEntry | null>(null);
  const [document, setDocument] = useState<FileDocument | null>(null);
  const [content, setContent] = useState("");
  const [savedContent, setSavedContent] = useState("");
  const [filter, setFilter] = useState("");
  const [loadingDirectory, setLoadingDirectory] = useState(true);
  const [loadingFile, setLoadingFile] = useState(false);
  const [saving, setSaving] = useState(false);

  const dirty = document !== null && content !== savedContent;

  const loadDirectory = useCallback(async (path: string) => {
    const id = ++directoryRequestId.current;
    setLoadingDirectory(true);
    try {
      const next = await api.files(path);
      if (directoryRequestId.current === id) setListing(next);
    } catch (error) {
      if (directoryRequestId.current === id) toast.push("error", (error as Error).message);
    } finally {
      if (directoryRequestId.current === id) setLoadingDirectory(false);
    }
  }, []);

  useEffect(() => {
    void loadDirectory("");
  }, [loadDirectory]);

  useEffect(() => {
    const beforeUnload = (event: BeforeUnloadEvent) => {
      if (!dirty) return;
      event.preventDefault();
    };
    window.addEventListener("beforeunload", beforeUnload);
    return () => window.removeEventListener("beforeunload", beforeUnload);
  }, [dirty]);

  const canLeaveEditor = () => !dirty || confirm("当前文件有未保存的修改，确定放弃吗？");

  const openDirectory = async (path: string) => {
    if (!canLeaveEditor()) return;
    requestId.current += 1;
    setSelected(null);
    setDocument(null);
    setContent("");
    setSavedContent("");
    setFilter("");
    await loadDirectory(path);
  };

  const loadFile = useCallback(async (entry: FileEntry) => {
    const id = ++requestId.current;
    setSelected(entry);
    setDocument(null);
    setLoadingFile(true);
    try {
      const next = await api.file(entry.path);
      if (requestId.current !== id) return;
      setDocument(next);
      setContent(next.content);
      setSavedContent(next.content);
    } catch (error) {
      if (requestId.current === id) toast.push("error", (error as Error).message);
    } finally {
      if (requestId.current === id) setLoadingFile(false);
    }
  }, [toast]);

  const openEntry = async (entry: FileEntry) => {
    if (entry.type === "directory") {
      await openDirectory(entry.path);
      return;
    }
    if (selected?.path === entry.path) return;
    if (!canLeaveEditor()) return;
    if (!entry.editable) {
      requestId.current += 1;
      setSelected(entry);
      setDocument(null);
      setContent("");
      setSavedContent("");
      return;
    }
    await loadFile(entry);
  };

  const save = useCallback(async () => {
    if (!document || !dirty) return;
    setSaving(true);
    try {
      const result = await api.saveFile(document.path, content, document.modifiedAt);
      const next = { ...document, content, size: result.size, modifiedAt: result.modifiedAt };
      setDocument(next);
      setSavedContent(content);
      setSelected((current) => current ? { ...current, size: result.size, modifiedAt: result.modifiedAt } : current);
      // 只有改到正在生效的配置文件才谈得上"应用"；别的文件就是写盘,别替它宣布生效。
      if (listing?.activeConfig?.path === document.path) {
        const applied = fmtApplied(result);
        toast.push(applied.tone, "文件已保存。" + applied.message);
      } else {
        toast.push("success", "文件已保存");
      }
      if (listing) void loadDirectory(listing.path);
    } catch (error) {
      toast.push("error", (error as Error).message);
    } finally {
      setSaving(false);
    }
  }, [content, dirty, document, listing, loadDirectory, toast]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "s" && document) {
        event.preventDefault();
        void save();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [document, save]);

  const entries = useMemo(() => {
    const needle = filter.trim().toLocaleLowerCase();
    const visible = (listing?.entries ?? []).filter((entry) => entry.path !== listing?.activeConfig?.path);
    if (!needle) return visible;
    return visible.filter((entry) => entry.name.toLocaleLowerCase().includes(needle));
  }, [filter, listing]);

  const breadcrumbs = useMemo(() => {
    const parts = listing?.path.split("/").filter(Boolean) ?? [];
    return parts.map((name, index) => ({ name, path: parts.slice(0, index + 1).join("/") }));
  }, [listing]);

  const formatJson = () => {
    try {
      setContent(JSON.stringify(JSON.parse(content), null, 2) + "\n");
    } catch (error) {
      toast.push("error", `JSON 格式错误: ${(error as Error).message}`);
    }
  };

  return (
    <div className="min-w-0">
      <PageHeader
        title="文件管理"
        desc={listing ? listing.root : "PicoOnebot 数据目录"}
        action={
          <Button variant="outline" onClick={() => listing && void loadDirectory(listing.path)} loading={loadingDirectory} disabled={!listing}>
            <RefreshCw className="h-4 w-4" /> 刷新
          </Button>
        }
      />

      <div className="grid min-w-0 gap-6 lg:grid-cols-[320px_minmax(0,1fr)]">
        <Card className="min-w-0 lg:h-[calc(100vh-12rem)] lg:min-h-[580px]">
          <CardHeader title="目录" desc={`${listing?.entries.length ?? 0} 项`} icon={<HardDrive className="h-4 w-4" />} />
          <div className="flex min-h-0 flex-col p-4 pt-3 sm:p-6 sm:pt-4 lg:h-[calc(100%-5rem)]">
            <div className="mb-3 flex min-h-8 flex-wrap items-center gap-1 text-xs text-pico-muted">
              <button type="button" onClick={() => void openDirectory("")} className="pico-ring rounded-lg p-1.5 hover:bg-pico-soft" title="根目录" aria-label="根目录">
                <Home className="h-3.5 w-3.5" />
              </button>
              {breadcrumbs.map((crumb) => (
                <span key={crumb.path} className="inline-flex min-w-0 items-center gap-1">
                  <ChevronRight className="h-3 w-3 shrink-0" />
                  <button type="button" onClick={() => void openDirectory(crumb.path)} className="pico-ring max-w-28 truncate rounded-lg px-1.5 py-1 font-bold hover:bg-pico-soft" title={crumb.name}>
                    {crumb.name}
                  </button>
                </span>
              ))}
            </div>

            <div className="relative mb-3">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-pico-muted" />
              <Input className="h-9 pl-9 text-xs" value={filter} onChange={(event) => setFilter(event.target.value)} placeholder="筛选当前目录" />
            </div>

            <div className="min-h-[280px] flex-1 overflow-y-auto rounded-2xl border border-pico-line bg-white/45 p-1.5">
              {listing?.activeConfig ? (
                <button
                  type="button"
                  onClick={() => void openEntry(listing.activeConfig!)}
                  className={cn(
                    "pico-ring mb-1 flex w-full items-center gap-3 rounded-xl border border-pico-line px-3 py-3 text-left transition",
                    selected?.path === listing.activeConfig.path ? "bg-pico-soft text-pico-deep" : "bg-white/70 text-pico-ink hover:bg-pico-softer"
                  )}
                >
                  <FileCode2 className="h-4 w-4 shrink-0 text-pico-deep" />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-xs font-extrabold">当前生效配置</span>
                    <span className="mt-0.5 block truncate font-mono text-[10px] text-pico-muted">{listing.activeConfig.path}</span>
                  </span>
                  <Badge tone="amber" className="shrink-0">开发者</Badge>
                </button>
              ) : null}
              {listing?.parent !== null && listing ? (
                <button type="button" onClick={() => void openDirectory(listing.parent ?? "")} className="pico-ring flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left text-sm text-pico-muted hover:bg-pico-soft">
                  <FolderOpen className="h-4 w-4" />
                  <span className="font-bold">..</span>
                </button>
              ) : null}
              {entries.map((entry) => (
                <button
                  key={entry.path}
                  type="button"
                  onClick={() => void openEntry(entry)}
                  className={cn(
                    "pico-ring flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left transition",
                    selected?.path === entry.path ? "bg-pico-soft text-pico-deep" : "text-pico-ink hover:bg-pico-softer"
                  )}
                >
                  <span className={cn("shrink-0", entry.type === "directory" ? "text-amber-500" : "text-pico-deep")}>{fileIcon(entry)}</span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-xs font-extrabold">{entry.name}</span>
                    <span className="mt-0.5 block text-[10px] text-pico-muted">
                      {entry.type === "directory" ? "目录" : formatSize(entry.size)}
                    </span>
                  </span>
                  {entry.type === "file" && !entry.editable ? <Badge tone="gray" className="shrink-0">只读</Badge> : null}
                  {entry.type === "directory" ? <ChevronRight className="h-3.5 w-3.5 shrink-0 text-pico-muted" /> : null}
                </button>
              ))}
              {!loadingDirectory && entries.length === 0 ? (
                <p className="px-3 py-8 text-center text-xs text-pico-muted">当前目录没有匹配项</p>
              ) : null}
            </div>
          </div>
        </Card>

        <Card className="min-w-0 lg:h-[calc(100vh-12rem)] lg:min-h-[580px]">
          <CardHeader
            title={selected?.name ?? "编辑器"}
            desc={document ? `${formatSize(document.size)} · ${fmtDateTime(document.modifiedAt)}` : selected ? `${formatSize(selected.size)} · ${fmtDateTime(selected.modifiedAt)}` : "未选择文件"}
            icon={<FileText className="h-4 w-4" />}
            action={document ? (
              <div className="flex shrink-0 items-center gap-1 sm:gap-2">
                {dirty ? <Badge tone="amber">未保存</Badge> : <Badge tone="green">已保存</Badge>}
                {document.name.toLowerCase().endsWith(".json") ? (
                  <Button variant="ghost" size="icon" onClick={formatJson} title="格式化 JSON" aria-label="格式化 JSON">
                    <Braces className="h-4 w-4" />
                  </Button>
                ) : null}
                <Button
                  variant="ghost"
                  size="icon"
                  onClick={() => {
                    void navigator.clipboard?.writeText(content);
                    toast.push("info", "文件内容已复制");
                  }}
                  title="复制内容"
                  aria-label="复制内容"
                >
                  <Copy className="h-4 w-4" />
                </Button>
                <Button variant="ghost" size="icon" onClick={() => setContent(savedContent)} disabled={!dirty} title="撤销修改" aria-label="撤销修改">
                  <RotateCcw className="h-4 w-4" />
                </Button>
                <Button onClick={() => void save()} loading={saving} disabled={!dirty}>
                  <Save className="h-4 w-4" /> 保存
                </Button>
              </div>
            ) : null}
          />

          <div className="h-[520px] min-w-0 p-4 pt-3 sm:p-6 sm:pt-4 lg:h-[calc(100%-5rem)]">
            {loadingFile ? (
              <div className="h-full animate-pulse rounded-2xl bg-pico-softer" />
            ) : document ? (
              <textarea
                value={content}
                onChange={(event) => setContent(event.target.value)}
                spellCheck={false}
                wrap="off"
                aria-label="文件内容"
                className="pico-ring h-full w-full resize-none overflow-auto rounded-2xl border-2 border-pico-line bg-white/80 p-4 font-mono text-xs leading-relaxed text-pico-ink transition focus:border-pico/70"
              />
            ) : selected ? (
              <div className="flex h-full flex-col items-center justify-center rounded-2xl border-2 border-dashed border-pico-line px-6 text-center">
                <AlertTriangle className="mb-3 h-8 w-8 text-amber-500" />
                <p className="text-sm font-extrabold text-pico-ink">此文件不能在线编辑</p>
                <p className="mt-1 text-xs text-pico-muted">仅支持不超过 1 MiB 的 UTF-8 文本文件</p>
              </div>
            ) : (
              <Empty title="选择一个文件" desc="文件内容会显示在这里" />
            )}
          </div>
        </Card>
      </div>
    </div>
  );
}
