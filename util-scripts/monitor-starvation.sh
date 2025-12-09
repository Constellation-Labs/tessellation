#!/bin/bash
# monitor-starvation.sh

DUMP_DIR=/home/admin/tessellation/l0/starvation_dumps
mkdir -p $DUMP_DIR

echo "Monitoring l0.service for CPU starvation warnings..."
echo "Thread dumps will be saved to $DUMP_DIR"
echo "Press Ctrl+C to stop"

LAST_DUMP=0
COOLDOWN=5  # Seconds between dumps to avoid spam

journalctl -u l0.service -f --no-pager --since "now" | while read -r line; do
  if echo "$line" | grep -q "responsiveness"; then
    NOW=$(date +%s)
    if (( NOW - LAST_DUMP >= COOLDOWN )); then
      TIMESTAMP=$(date +%Y%m%d_%H%M%S)
      FILENAME="$DUMP_DIR/starvation_${TIMESTAMP}.txt"

      echo "$(date): Starvation detected! Capturing thread dump..."
      echo "Message: $line"

      # Resolve PID fresh each time
      PID=$(jps | grep "cl-node.jar" | awk '{print $1}')

      if [ -z "$PID" ]; then
        echo "Error: cl-node.jar not running, skipping dump"
        echo "---"
        continue
      fi

      jcmd "$PID" Thread.print > "$FILENAME" 2>&1
      echo "Saved to $FILENAME"
      echo "---"
      
      LAST_DUMP=$NOW
    fi
  fi
done
