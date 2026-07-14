#!/usr/bin/env bash
#
# migrate-data.sh — clone tn1-3 chain data onto the Hetzner cluster, STAGED.
#
#   deploy/migrate-data.sh setup-auth    # one-time: dedicated transfer keypair
#   deploy/migrate-data.sh plan          # show pairs, sizes, dest capacity
#   deploy/migrate-data.sh sync [tnN]    # launch/refresh the sync, DETACHED on the tn boxes
#   deploy/migrate-data.sh status [tnN]  # rsync running? dest size, remote log tail
#   deploy/migrate-data.sh verify        # dry-run delta per pair (0 diffs = converged)
#
# Data flows DIRECTLY tn_i -> hetzner_node_i (this machine only orchestrates). Auth is
# a dedicated ed25519 transfer key (setup-auth): private key on the tn boxes, public key
# on the Hetzner nodes' admin account locked to a forced rsync-only command +
# restrict + from="<tn IPs>" — so a compromised tn box can drive only the rsync, not a
# shell/sudo. Unattended-capable, revocable by deleting one authorized_keys line, never
# exposes personal keys to the tn boxes. Safe to re-run; each pass is an rsync delta.
# (Still: delete the authorized_keys line + key after cutover — it outlives the window.)
#
# The copy lands in a STAGING dir (default /opt/tessellation/tn-data for l0,
# /opt/tessellation/tn-data-l1 for l1), NOT gl0-data/gl1-data — the cluster may be
# running its own chain meanwhile. At cutover (tn services stopped, final `sync` +
# `verify` clean): stop containers, `mv gl0-data gl0-data.dev && mv tn-data gl0-data`
# (and the same for gl1-data <- tn-data-l1), then deploy with the migration knobs.
# Mirror BOTH layers by running once per layer:  LAYER=l0 sync  then  LAYER=l1 sync
#
# Env overrides:
#   LAYER     which layer to mirror: l0 (default, ~168G) or l1 (~330M)
#   BWLIMIT   rsync --bwlimit in KB/s (default 40000 ~ 40MB/s; tn nodes are LIVE)
#   DEST      staging dir on the Hetzner nodes (default per LAYER)
#   SUBDIR    limit to one data subdir (e.g. SUBDIR=snapshot_info — smoke tests,
#             targeted re-syncs)
#   TN_HOSTS  comma-separated source ssh hosts (default tn1,tn2,tn3 from ~/.ssh/config)
#
# Excluded from the copy: *_bkp dirs (35G of backups on tn1) and the two helper *.sh
# scripts. Everything else under l0/data is chain state the rollback needs.
# rsync -H is REQUIRED: incremental_snapshot keeps a dual ordinal/ + hash/ index that
# is hardlinked — without -H the copy inflates ~2x and breaks the layout.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CMD="${1:-plan}"
ONLY="${2:-}"

BWLIMIT="${BWLIMIT:-40000}"
SUBDIR="${SUBDIR:-}"

log() { printf '\033[1;34m[migrate]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[migrate] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# LAYER selects which layer's chain data to mirror. Each lands in its own staging dir so
# both can be synced independently (run the tool once per layer):
#   LAYER=l0 (default) — global-L0, ~168G  -> /opt/tessellation/tn-data
#   LAYER=l1           — global-L1, ~330M  -> /opt/tessellation/tn-data-l1
LAYER="${LAYER:-l0}"
case "$LAYER" in
  l0) SRC_BASE="/home/admin/tessellation/l0/data"; DEFAULT_DEST="/opt/tessellation/tn-data" ;;
  l1) SRC_BASE="/home/admin/tessellation/l1/data"; DEFAULT_DEST="/opt/tessellation/tn-data-l1" ;;
  *) die "unknown LAYER '$LAYER' (l0|l1)" ;;
esac
DEST="${DEST:-$DEFAULT_DEST}"

IFS=',' read -r -a TN <<< "${TN_HOSTS:-tn1,tn2,tn3}"

# Hetzner node IPs, same source of truth as deploy-cluster.sh
command -v terraform >/dev/null || die "terraform not found"
pushd "$ROOT/deploy/terraform" >/dev/null
mapfile -t HN_IPS < <(terraform output -json node_public_ips 2>/dev/null \
    | python3 -c "import json,sys;[print(x) for x in json.load(sys.stdin)]")
popd >/dev/null
[ "${#HN_IPS[@]}" -eq 3 ] || die "expected 3 node IPs from terraform output, got ${#HN_IPS[@]}"
[ "${#TN[@]}" -eq 3 ] || die "expected 3 tn hosts, got ${#TN[@]}"

SRC="$SRC_BASE${SUBDIR:+/$SUBDIR}"
DST_PATH="$DEST${SUBDIR:+/$SUBDIR}"

RSYNC_FILTERS=(--exclude='*_bkp' --exclude='*.sh')
TRANSFER_KEY="/home/admin/.ssh/tn_hetzner_transfer"   # path ON the tn boxes; absolute —
# it rides inside rsync's -e '...' where no shell ever expands $HOME/~
INNER_SSH="ssh -i $TRANSFER_KEY -o StrictHostKeyChecking=accept-new"
# nice/ionice: best-effort LOWEST (-c2 -n7) rather than idle (-c3) — idle class risks
# total starvation under the live node's constant snapshot writes; best-effort-low
# still yields under load, and --bwlimit caps the network side.
RSYNC_CMD="nice -n 19 ionice -c2 -n7 rsync -aH --delete --partial --bwlimit=$BWLIMIT \
  ${RSYNC_FILTERS[*]} --stats --human-readable \
  -e '$INNER_SSH' \
  $SRC/ admin@__HN_IP__:$DST_PATH/"

pair_desc() { echo "${TN[$1]} -> ${HN_IPS[$1]} ($SRC/ -> $DST_PATH/)"; }

case "$CMD" in
  setup-auth)
    LOCAL_KEY="$ROOT/.migrate-transfer-key"
    if [ ! -f "$LOCAL_KEY" ]; then
      log "generating transfer keypair"
      ssh-keygen -t ed25519 -f "$LOCAL_KEY" -N "" -C "tn->hetzner-migration-transfer" -q
    fi
    PUB=$(cat "$LOCAL_KEY.pub")
    TN_SRC_IPS=$(for t in "${TN[@]}"; do ssh -G "$t" | awk '/^hostname /{print $2}'; done | paste -sd, -)
    # Forced-command wrapper: this key may ONLY drive the rsync it's for. Without it a
    # `from=`-only key grants a full interactive shell on the Hetzner admin account
    # (NOPASSWD sudo + docker group) — so a compromise of any tn box during the
    # migration window would become root on all three new nodes. restrict also strips
    # pty/agent/port/X11 forwarding. (The key still can't be used outside the tn IPs.)
    # Kept in the admin home (NOT under $DEST, which rsync --delete would wipe).
    WRAPPER_PATH='$HOME/.rsync-only.sh'
    WRAPPER='#!/bin/sh
case "$SSH_ORIGINAL_COMMAND" in
  "rsync --server "*) exec $SSH_ORIGINAL_COMMAND ;;
  *) echo "this key only permits rsync" >&2; exit 1 ;;
esac'
    AUTH_LINE="command=\"$WRAPPER_PATH\",restrict,from=\"$TN_SRC_IPS\" $PUB"
    for i in 0 1 2; do
      log "installing restricted (rsync-only) public key on admin@${HN_IPS[$i]}"
      ssh -o StrictHostKeyChecking=accept-new "admin@${HN_IPS[$i]}" \
        "printf '%s\n' '$WRAPPER' > \$HOME/.rsync-only.sh && chmod 700 \$HOME/.rsync-only.sh; \
         mkdir -p ~/.ssh && { grep -qF '$PUB' ~/.ssh/authorized_keys 2>/dev/null || echo '$AUTH_LINE' >> ~/.ssh/authorized_keys; }"
    done
    for t in "${TN[@]}"; do
      log "installing private key on $t"
      scp -q "$LOCAL_KEY" "$t:.ssh/tn_hetzner_transfer"
      ssh "$t" "chmod 600 ~/.ssh/tn_hetzner_transfer"
    done
    log "done — test with: deploy/migrate-data.sh verify"
    ;;

  plan)
    log "pairs (BWLIMIT=${BWLIMIT}KB/s, dest=$DST_PATH):"
    for i in 0 1 2; do log "  $(pair_desc $i)"; done
    for i in 0 1 2; do
      log "--- ${TN[$i]}: source size ---"
      ssh -o StrictHostKeyChecking=accept-new "${TN[$i]}" \
        "du -s --block-size=1G $SRC 2>/dev/null | awk '{print \$1\"G\"}'" 2>/dev/null || echo "  (du failed)"
      log "--- ${HN_IPS[$i]}: dest capacity ---"
      ssh -o StrictHostKeyChecking=accept-new "admin@${HN_IPS[$i]}" \
        "df -h /opt/tessellation | tail -1" 2>/dev/null || echo "  (df failed)"
    done
    ;;

  sync)
    for i in 0 1 2; do
      [ -n "$ONLY" ] && [ "${TN[$i]}" != "$ONLY" ] && continue
      hn="${HN_IPS[$i]}"
      log "sync $(pair_desc $i)  (remote log: ~/tn-migrate-sync.log on ${TN[$i]})"
      ssh -o StrictHostKeyChecking=accept-new "admin@$hn" "mkdir -p $DST_PATH"
      # Stage the command as a script on the tn box, then run it DETACHED in a second
      # ssh call. MUST be two calls: combining them as `cat > f && setsid ... &` makes
      # the `&` background the whole list, which reassigns stdin to /dev/null before
      # cat reads it -> silent empty script (bitten 2026-07-02).
      ssh -o StrictHostKeyChecking=accept-new "${TN[$i]}" \
        "cat > ~/.migrate-run.sh" <<< "${RSYNC_CMD//__HN_IP__/$hn}"
      ssh -o StrictHostKeyChecking=accept-new "${TN[$i]}" \
        "test -s ~/.migrate-run.sh || { echo 'EMPTY run script'; exit 1; }; setsid nohup bash ~/.migrate-run.sh > ~/tn-migrate-sync.log 2>&1 < /dev/null & echo detached"
    done
    log "transfers launched (detached). Poll with: deploy/migrate-data.sh status"
    ;;

  status)
    for i in 0 1 2; do
      [ -n "$ONLY" ] && [ "${TN[$i]}" != "$ONLY" ] && continue
      hn="${HN_IPS[$i]}"
      # -x: exact process name — a -f pattern matches the ssh wrapper's own cmdline
      running=$(ssh -o StrictHostKeyChecking=accept-new "${TN[$i]}" \
        "pgrep -xc rsync 2>/dev/null || echo 0")
      dest_size=$(ssh -o StrictHostKeyChecking=accept-new "admin@$hn" \
        "du -s --block-size=1G $DEST 2>/dev/null | awk '{print \$1}'" || echo "?")
      log "${TN[$i]} -> $hn: rsync running=$running  dest=${dest_size}G"
      ssh -o StrictHostKeyChecking=accept-new "${TN[$i]}" \
        "tail -3 ~/tn-migrate-sync.log 2>/dev/null" | sed 's/^/    /'
    done
    ;;

  verify)
    rc=0
    for i in 0 1 2; do
      [ -n "$ONLY" ] && [ "${TN[$i]}" != "$ONLY" ] && continue
      hn="${HN_IPS[$i]}"
      log "verify $(pair_desc $i)"
      # itemized dry run: any output line = a difference still to sync
      diffs=$(ssh -o StrictHostKeyChecking=accept-new "${TN[$i]}" \
        "rsync -aHn --delete ${RSYNC_FILTERS[*]} --out-format='%n' \
          -e '$INNER_SSH' \
          $SRC/ admin@$hn:$DST_PATH/ 2>/dev/null | head -20" || echo "__VERIFY_FAILED__")
      if [ -z "$diffs" ]; then
        log "  CONVERGED (no pending changes)"
      else
        rc=1
        log "  pending changes (first 20):"; printf '%s\n' "$diffs" | sed 's/^/    /'
      fi
    done
    exit $rc
    ;;

  *) die "unknown command '$CMD' (setup-auth|plan|sync|status|verify)" ;;
esac
