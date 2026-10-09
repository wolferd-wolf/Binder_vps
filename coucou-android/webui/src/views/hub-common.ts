// SPRINT 6.5 @Cline — hub shared types (WebUI lane only).
import type { IslandViewName } from "../core/layout";
import type { HubTask } from "./hub-helpers";

export interface HubActions {
  setView(v: IslandViewName): void;
}

export type { HubTask };

export function parseTasks(raw: string | null): HubTask[] {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) return [];
    const out: HubTask[] = [];
    for (const item of parsed) {
      if (typeof item !== "object" || item === null) continue;
      const rec = item as Record<string, unknown>;
      const id = Number(rec["id"] ?? 0);
      const text = String(rec["text"] ?? "");
      if (!Number.isFinite(id) || text.length === 0) continue;
      out.push({
        id,
        text,
        isDone: Boolean(rec["isDone"] ?? rec["done"] ?? false),
        isTask: rec["isTask"] === undefined ? true : Boolean(rec["isTask"]),
      });
    }
    return out;
  } catch {
    return [];
  }
}
