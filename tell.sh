#!/usr/bin/env bash
# Usage: ./tell.sh <sender> <target> <message>
SENDER="${1:-human}"
TARGET="$2"
shift 2
MSG="$*"

mkdir -p /workspaces/Binder_vps/.agents/inbox /workspaces/Binder_vps/.agents/locks

case "$(echo "$TARGET" | tr '[:upper:]' '[:lower:]')" in
  agy|antigravity|0)   PANE="agents:0.0"; TARGET_NAME="AGY" ;;
  boss|human|1)        PANE="agents:0.1"; TARGET_NAME="Boss" ;;
  opencode|oc|2)       PANE="agents:0.2"; TARGET_NAME="OpenCode" ;;
  buffy|freebuff|3)    PANE="agents:0.3"; TARGET_NAME="Buffy" ;;
  cline|4)             PANE="agents:0.4"; TARGET_NAME="Cline" ;;
  *)
    echo "Unknown target: $TARGET"
    exit 1
    ;;
esac

LOCK_FILE="/workspaces/Binder_vps/.agents/locks/${TARGET_NAME}.lock"
if [ -f "$LOCK_FILE" ] && [ $(( $(date +%s) - $(stat -c %Y "$LOCK_FILE") )) -lt 15 ]; then
  echo "BLOCKED: @$TARGET_NAME pinged recently. Queued in inbox."
  echo "[$(date -u +"%T")] From @$SENDER: $MSG" >> "/workspaces/Binder_vps/.agents/inbox/${TARGET_NAME}.txt"
  exit 0
fi
touch "$LOCK_FILE"

PANE_CONTENT=$(tmux capture-pane -t "$PANE" -p 2>/dev/null | tail -n 5)
if echo "$PANE_CONTENT" | grep -qiE "thinking|executing|running|waiting|progress"; then
  echo "BUSY: @$TARGET_NAME running. Queuing in inbox."
  echo "[$(date -u +"%T")] From @$SENDER: $MSG" >> "/workspaces/Binder_vps/.agents/inbox/${TARGET_NAME}.txt"
  exit 0
fi

FORMATTED_MSG="[Message from @$SENDER]: $MSG"
tmux send-keys -t "$PANE" -l "$FORMATTED_MSG"
sleep 0.2
tmux send-keys -t "$PANE" C-m
echo "$(date -u +"%Y-%m-%d %H:%M:%S") | @$SENDER -> @$TARGET_NAME | $MSG" >> /workspaces/Binder_vps/.agents/chat.log
echo "Delivered to @$TARGET_NAME ($PANE)."
