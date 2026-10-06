#!/usr/bin/env bash
# Usage: ./tell.sh <sender> <target> <message>
SENDER="${1:-human}"
TARGET="$2"
shift 2
MSG="$*"

mkdir -p /workspaces/Binder_vps/.agents/locks

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

# 1. Anti-Loop / Cooldown Lock (30-second guard)
LOCK_FILE="/workspaces/Binder_vps/.agents/locks/${TARGET_NAME}.lock"
if [ -f "$LOCK_FILE" ]; then
  LAST_PING=$(stat -c %Y "$LOCK_FILE" 2>/dev/null || echo 0)
  CURRENT_TIME=$(date +%s)
  if [ $(( CURRENT_TIME - LAST_PING )) -lt 30 ]; then
    echo "DROPPED: @$TARGET_NAME was messaged less than 30s ago. Anti-spam engaged."
    echo "$(date -u +"%Y-%m-%d %H:%M:%S") | BLOCKED LOOP @$SENDER -> @$TARGET_NAME | $MSG" >> /workspaces/Binder_vps/.agents/chat.log
    exit 0
  fi
fi

# 2. Busy Check (Inspect last 5 lines of target pane)
PANE_CONTENT=$(tmux capture-pane -t "$PANE" -p 2>/dev/null | tail -n 5)
if echo "$PANE_CONTENT" | grep -qiE "thinking|executing|running|waiting|progress"; then
  echo "BUSY: @$TARGET_NAME is executing. Message dropped to prevent corrupting inputs."
  exit 0
fi

# Update Lockfile
touch "$LOCK_FILE"

# 3. Read Active Project Context
CURRENT_PROJ=$(cat /workspaces/Binder_vps/.agents/ACTIVE_PROJECT 2>/dev/null || echo "General")

# 4. Format Message with Project & Anti-Chitchat Warning
FORMATTED_MSG="[Message from @$SENDER | Project: $CURRENT_PROJ]: $MSG (DO NOT acknowledge or reply. Execute quietly.)"

# 5. Safe Keystroke Injection
tmux send-keys -t "$PANE" -l "$FORMATTED_MSG"
sleep 0.2
tmux send-keys -t "$PANE" C-m

# 6. Audit Log
echo "$(date -u +"%Y-%m-%d %H:%M:%S") | @$SENDER -> @$TARGET_NAME | $MSG" >> /workspaces/Binder_vps/.agents/chat.log
echo "Delivered to @$TARGET_NAME."
