// SPRINT 6.5 @Cline — Assistant Hub bridge extensions (WebUI-only).
// OpenCode's Kotlin lane (openFilePicker / getTasksJson / checkMicPermission)
// may not have landed yet, so every call is defensive: prefer the named
// `window.CoucouAndroid` method when it exists, otherwise fall back to the
// generic `CoucouNative.invoke` escape hatch (which safely resolves null for
// unmodelled commands). Nothing here touches Kotlin or Gradle.
export interface HubFile {
  name: string;
  size: number;
  mimeType: string;
  uri: string;
}

export interface HubTask {
  id: number;
  text: string;
  isDone: boolean;
  isTask?: boolean;
}

declare global {
  interface Window {
    IslandBridge?: {
      openFilePicker?: () => unknown;
      getTasksJson?: () => unknown;
      toggleNote?: (id: number) => unknown;
    };
  }
}

/** Last file reported by Kotlin via `window.CoucouAndroid.onFileSelected`. */
export function getLastPickedFile(): HubFile | null {
  try {
    const w = window as unknown as { __coucouLastFile?: HubFile | null };
    return w.__coucouLastFile ?? null;
  } catch {
    return null;
  }
}

/** Called by main.ts when Kotlin fires `onFileSelected`. Stores + fans out. */
export function handlePickedFile(raw: string | HubFile): HubFile | null {
  try {
    const parsed: HubFile =
      typeof raw === "string" ? (JSON.parse(raw) as HubFile) : raw;
    if (!parsed || typeof parsed.name !== "string") return null;
    const w = window as unknown as {
      __coucouLastFile?: HubFile | null;
    };
    w.__coucouLastFile = {
      name: String(parsed.name),
      size: Number(parsed.size ?? 0),
      mimeType: String(parsed.mimeType ?? "application/octet-stream"),
      uri: String(parsed.uri ?? ""),
    };
    window.dispatchEvent(new Event("coucou:file-selected"));
    try {
      const island = (window as unknown as { __coucouIsland?: { alert(v: string): void } })
        .__coucouIsland;
      island?.alert("vault");
    } catch {
      /* navigation is best-effort; the vault view also listens for the event */
    }
    return w.__coucouLastFile ?? null;
  } catch {
    return null;
  }
}

/** Human-readable file size for the vault chip. */
export function formatSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return "";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
