#!/usr/bin/env bash
# Usage:
#   Normal:  ./tell.sh <sender> <target> "<message>"
#   Steer:   ./tell.sh --steer <sender> <target> "<message>"

STEER_MODE=false
if [[ "$1" == "--steer" || "$1" == "-s" ]]; then
  STEER_MODE=true
  shift
fi

SENDER="${1:-human}"
TARGET="$2"
shift 2
MSG="$*"

LOG_FILE="/workspaces/Binder_vps/.agents/chat.log"
mkdir -p /workspaces/Binder_vps/.agents/inbox /workspaces/Binder_vps/.agents/locks

# Map agent names to tmux panes
case "$(echo "$TARGET" | tr '[:upper:]' '[:lower:]')" in
  agy|antigravity|0)   PANE="agents:0.0"; TARGET_NAME="AGY" ;;
  opencode|oc|1)       PANE="agents:0.1"; TARGET_NAME="OpenCode" ;;
  buffy|freebuff|2)    PANE="agents:0.2"; TARGET_NAME="Buffy" ;;
  cline|3)             PANE="agents:0.3"; TARGET_NAME="Cline" ;;
  *)
    echo "Unknown target: $TARGET (Use: agy, opencode, buffy, cline)"
    exit 1
    ;;
esac

LOCK_FILE="/workspaces/Binder_vps/.agents/locks/${TARGET_NAME}.lock"

# --- STEERING PATH (Immediate Interrupt & Redirect) ---
if [ "$STEER_MODE" = true ]; then
  echo ">>> STEERING @$TARGET_NAME (Interrupting active execution)..."
  # Send Ctrl+C to abort current execution/thinking turn
  tmux send-keys -t "$PANE" C-c
  sleep 0.3
  tmux send-keys -t "$PANE" C-c
  sleep 0.3

  FORMATTED_MSG="[STEERING DIRECTIVE from @$SENDER]: $MSG"
  tmux send-keys -t "$PANE" -l "$FORMATTED_MSG"
  sleep 0.2
  tmux send-keys -t "$PANE" C-m

  touch "$LOCK_FILE"
  echo "$(date -u +"%Y-%m-%d %H:%M:%S") | [STEER] @$SENDER -> @$TARGET_NAME | $MSG" >> "$LOG_FILE"
  echo "Steering directive delivered to @$TARGET_NAME."
  exit 0
fi

# --- NORMAL HANDOFF PATH ---
if [ -f "$LOCK_FILE" ] && [ $(( $(date +%s) - $(stat -c %Y "$LOCK_FILE") )) -lt 30 ]; then
  echo "BLOCKED: @$TARGET_NAME was pinged recently. Queued in inbox."
  echo "[$(date -u +"%T")] From @$SENDER: $MSG" >> "/workspaces/Binder_vps/.agents/inbox/${TARGET_NAME}.txt"
  exit 0
fi

PANE_CONTENT=$(tmux capture-pane -t "$PANE" -p 2>/dev/null | tail -n 5)
if echo "$PANE_CONTENT" | grep -qiE "thinking|executing|running|waiting|progress"; then
  echo "BUSY: @$TARGET_NAME running. Queued in inbox."
  echo "[$(date -u +"%T")] From @$SENDER: $MSG" >> "/workspaces/Binder_vps/.agents/inbox/${TARGET_NAME}.txt"
  exit 0
fi

touch "$LOCK_FILE"
FORMATTED_MSG="[Message from @$SENDER]: $MSG"
tmux send-keys -t "$PANE" -l "$FORMATTED_MSG"
sleep 0.2
tmux send-keys -t "$PANE" C-m

echo "$(date -u +"%Y-%m-%d %H:%M:%S") | @$SENDER -> @$TARGET_NAME | $MSG" >> "$LOG_FILE"
echo "Delivered to @$TARGET_NAME."
