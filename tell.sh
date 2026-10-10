#!/usr/bin/env bash
# Usage: /workspaces/Binder_vps/tell.sh <sender> <target> <message>
SENDER="${1:-human}"
TARGET="$2"
shift 2
MSG="$*"

LOG_FILE="/workspaces/Binder_vps/.agents/chat.log"
mkdir -p /workspaces/Binder_vps/.agents/locks

# Map agent names to tmux target panes
case "$(echo "$TARGET" | tr '[:upper:]' '[:lower:]')" in
  agy|antigravity|0)   PANE="agents:0.0"; TARGET_NAME="AGY" ;;
  opencode|oc|1)       PANE="agents:0.1"; TARGET_NAME="OpenCode" ;;
  buffy|freebuff|2)    PANE="agents:0.2"; TARGET_NAME="Buffy" ;;
  cline|3)             PANE="agents:0.3"; TARGET_NAME="Cline" ;;
  *)
    # Fallback to direct pane index if numbers were passed
    if [[ "$TARGET" =~ ^[0-3]$ ]]; then
      PANE="agents:0.$TARGET"
      TARGET_NAME="Pane_$TARGET"
    else
      echo "Unknown target: $TARGET (Valid: agy, opencode, buffy, cline)"
      exit 1
    fi
    ;;
esac

FORMATTED_MSG="[Message from @$SENDER]: $MSG"

# Send keys literally with buffer settling time
tmux send-keys -t "$PANE" -l "$FORMATTED_MSG"
sleep 0.2
tmux send-keys -t "$PANE" C-m

echo "$(date -u +"%Y-%m-%d %H:%M:%S") | @$SENDER -> @$TARGET_NAME ($PANE) | $MSG" >> "$LOG_FILE"
echo "Delivered to @$TARGET_NAME."
