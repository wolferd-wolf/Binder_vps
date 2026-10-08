// Note form submission - prevents page navigation, saves via IPC only
import { Bridge } from "../core/bridge";

/** Handle Note form submission - prevents page navigation, saves via IPC */
export function handleNoteEnter(
  inputEl: HTMLInputElement,
  onNoteAdded: (text: string) => void | Promise<void>,
) {
  inputEl.addEventListener("keydown", (e: KeyboardEvent) => {
    if (e.key !== "Enter") return;
    const val = inputEl.value.trim();
    if (!val) return;
    // CRITICAL: stay inside the overlay, never submit/redirect.
    e.preventDefault();
    e.stopPropagation();
    inputEl.value = "";
    void Promise.resolve()
      .then(() => Bridge.addNote(val, true))
      .then(() => onNoteAdded(val))
      .catch((err) => {
        console.error("[coucou] note save failed", err);
        inputEl.value = val;
      });
  });
}

