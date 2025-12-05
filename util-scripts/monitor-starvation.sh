#!/bin/bash
# monitor-starvation.sh

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

DUMP_DIR=/home/admin/tessellation/l0/starvation_dumps
mkdir -p $DUMP_DIR

echo "Monitoring l0.service for CPU starvation warnings..."
echo "Thread dumps will be saved to $DUMP_DIR"
echo "Press Ctrl+C to stop"

LAST_DUMP=0
COOLDOWN=5  # Seconds between dumps to avoid spam

journalctl -u l0.service -f --no-pager | while read -r line; do
  if echo "$line" | grep -q "responsiveness"; then
    NOW=$(date +%s)
    if (( NOW - LAST_DUMP >= COOLDOWN )); then
      TIMESTAMP=$(date +%Y%m%d_%H%M%S)
      FILENAME="$DUMP_DIR/starvation_${TIMESTAMP}.txt"
      
      echo "$(date): Starvation detected! Capturing thread dump..."
      echo "Message: $line"
      
      jcmd $PID Thread.print > "$FILENAME" 2>&1
      echo "Saved to $FILENAME"
      echo "---"
      
      LAST_DUMP=$NOW
    fi
  fi
done
