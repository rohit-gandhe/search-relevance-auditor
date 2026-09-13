#!/usr/bin/env bash
# The whole operational surface. Three processes, started together, never by hand.
#
# Starting one by hand creates a duplicate, and a duplicate worker silently takes
# half the approvals — with older code and a stale rule set. From the outside that
# presents as "Slack is failing", intermittently. Check `status` says procs : 3
# before presenting.
set -euo pipefail
cd "$(dirname "$0")/.."

PROCS=(ui socket worker)
LOGS=logs

start() {
  mkdir -p "$LOGS"
  ./gradlew -q classes            # compile once, so three JVMs do not race on it
  for p in "${PROCS[@]}"; do
    if pgrep -f "labs.augmentor.auditor.App $p" >/dev/null 2>&1; then
      echo "  $p already running"
      continue
    fi
    nohup ./gradlew -q run --args="$p" > "$LOGS/$p.log" 2>&1 &
    echo "  $p starting"
  done
  # Wait for all three, not just the UI. The socket connects to Slack before it
  # registers, so a status check that only waits on the port reports procs : 2 for
  # a stack that is fine — and "NOT READY" is the last thing anyone needs to read
  # while walking to the front of a room.
  echo "  waiting for all three"
  for _ in $(seq 1 90); do
    up=0
    for p in "${PROCS[@]}"; do
      pgrep -f "labs.augmentor.auditor.App $p" >/dev/null 2>&1 && up=$((up+1))
    done
    [ "$up" -eq 3 ] && curl -sf -o /dev/null http://localhost:7070 && break
    sleep 1
  done
  status
}

stop() {
  pkill -f "labs.augmentor.auditor.App" 2>/dev/null || true
  # Gradle daemons hold the app as a child; give them a moment to reap it.
  sleep 2
  rm -f data/*.pid
  echo "  stopped"
}

status() {
  local n=0
  for p in "${PROCS[@]}"; do
    if pgrep -f "labs.augmentor.auditor.App $p" >/dev/null 2>&1; then
      echo "  $p     up"
      n=$((n+1))
    else
      echo "  $p     down"
    fi
  done
  echo "  procs : $n"
  curl -sf -o /dev/null http://localhost:7070 && echo "  ui    : http://localhost:7070" \
                                              || echo "  ui    : not answering"
  [ "$n" -eq 3 ] || echo "  NOT READY — three processes are required"
}

case "${1:-status}" in
  start)   start ;;
  stop)    stop ;;
  restart) stop; start ;;
  status)  status ;;
  logs)    tail -n 40 -f "$LOGS"/*.log ;;
  *) echo "usage: $0 {start|stop|restart|status|logs}"; exit 1 ;;
esac
