#!/usr/bin/env bash

RUN_LOG_FILE="/tessellation/logs/health-check.log"
/tessellation/health-check-inner.sh 2>&1 | tee -a $RUN_LOG_FILE
# Capture Java’s exit code (PIPESTATUS[0] is Java; [1] would be tee)
exit_code=${PIPESTATUS[0]}
exit $exit_code
