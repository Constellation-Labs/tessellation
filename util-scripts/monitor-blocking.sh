#!/bin/bash
# monitor-blocking.sh

DUMP_DIR=/home/admin/tessellation/l0/blocking_dumps
mkdir -p $DUMP_DIR

echo "Monitoring for blocked compute threads..."
echo "Dumps will be saved to $DUMP_DIR"
echo "Press Ctrl+C to stop"

PREV_BUSY=""
while true; do
  # Resolve PID fresh each iteration
  PID=$(jps | grep "cl-node.jar" | awk '{print $1}')

  if [ -z "$PID" ]; then
    echo "$(date): cl-node.jar not running, waiting..."
    sleep 5
    PREV_BUSY=""
    continue
  fi

  DUMP=$(jcmd "$PID" Thread.print 2>/dev/null)

  if [ -z "$DUMP" ]; then
    echo "$(date): Failed to get thread dump, process may have exited"
    sleep 5
    PREV_BUSY=""
    continue
  fi
  
  # Find io-compute threads (not blocker) that are RUNNABLE and not parked
  BUSY=$(echo "$DUMP" | grep -B1 "State: RUNNABLE" | grep '"io-compute-[0-9]"' | sed 's/.*"\(io-compute-[0-9]\)".*/\1/' | sort | uniq)
  
  if [ -n "$BUSY" ]; then
    for thread in $BUSY; do
      if echo "$PREV_BUSY" | grep -q "$thread"; then
        TIMESTAMP=$(date +%Y%m%d_%H%M%S)
        FILENAME="$DUMP_DIR/blocked_${TIMESTAMP}.txt"
        echo "$DUMP" > "$FILENAME"
        echo "$(date): $thread stuck RUNNABLE across samples - saved to $FILENAME"
        sleep 5  # Cooldown after finding something
        break
      fi
    done
  fi
  
  PREV_BUSY="$BUSY"
  sleep 2
done
