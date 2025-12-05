#!/bin/bash
# monitor-blocking.sh

# Get PID from argument or find via jps
if [ -n "$1" ]; then
  PID=$1
else
  # Find cl-node.jar processes
  MATCHES=$(jps | grep "cl-node.jar")
  COUNT=$(echo "$MATCHES" | grep -c "cl-node.jar")
  
  if [ "$COUNT" -eq 0 ]; then
    echo "Error: No cl-node.jar process found"
    exit 1
  elif [ "$COUNT" -gt 1 ]; then
    echo "Error: Multiple cl-node.jar processes found:"
    echo "$MATCHES"
    echo "Please specify PID as argument: $0 <pid>"
    exit 1
  fi
  
  PID=$(echo "$MATCHES" | awk '{print $1}')
fi

# Verify PID is valid
if ! kill -0 "$PID" 2>/dev/null; then
  echo "Error: PID $PID is not running"
  exit 1
fi

DUMP_DIR=/home/admin/tessellation/l0/blocking_dumps
mkdir -p $DUMP_DIR

echo "Monitoring PID $PID for blocked compute threads..."
echo "Dumps will be saved to $DUMP_DIR"
echo "Press Ctrl+C to stop"

PREV_BUSY=""

while true; do
  DUMP=$(jcmd $PID Thread.print 2>/dev/null)
  
  if [ -z "$DUMP" ]; then
    echo "$(date): Failed to get thread dump, process may have exited"
    sleep 5
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
