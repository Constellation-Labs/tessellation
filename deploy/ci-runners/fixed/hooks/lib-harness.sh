#!/usr/bin/env bash
#
# Shared scoping helpers for the job hooks. Sourced, not executed.
#
# WHY THIS EXISTS: the hooks used to reclaim state with `docker ps -aq` ->
# `docker rm -f`, `docker volume prune -f`, and `docker system prune -af
# --volumes`. Every one of those acts on the WHOLE HOST. That is harmless on a
# dedicated runner and destructive anywhere else — on the nightly monitoring box
# it removes grafana/prometheus/clickhouse and then prunes their named volumes,
# discarding months of retained data on the very first job, silently (both hooks
# always exit 0).
#
# Everything below targets only what the E2E harness itself creates, which is
# also strictly correct on a dedicated runner: a box with nothing else on it
# loses nothing by being precise. There is deliberately no "host-wide" mode.

# --- containers -------------------------------------------------------------
#
# Two sources, unioned, because neither is sufficient on its own:
#
#  1. Membership of `tessellation_common`. compose-runner.sh:175 creates it and
#     every harness container joins it: gl0/gl1 (docker-compose.test.yaml),
#     ml0/cl1/dl1 (docker-compose.metagraph-test.yaml), snapshot-streaming and
#     its postgres (snapshot-streaming/docker-compose.yaml), tx-sender
#     (compose-runner.sh:535).
#
#     This is the ONLY source that can safely identify the harness's own
#     monitoring stack, whose containers docker/monitoring/docker-compose.local.yaml
#     names `clickhouse`, `prometheus` and `grafana` — the exact names the
#     nightly monitoring box uses for its production stack. Those three must
#     never be matched by name; the production ones run on `network_mode: host`
#     and are invisible here, which is what keeps them safe.
#
#  2. A name pattern. Docker detaches a container from its networks when it
#     stops, so source 1 cannot see exited containers — precisely what
#     job-started.sh exists to clean up after a hard failure. The harness's node
#     container names are deterministic: `${role}${CONTAINER_NAME_SUFFIX}`, with
#     the suffix set to `-$i` by docker-env-setup.sh:153.
harness_containers() {
  {
    docker network inspect tessellation_common \
      -f '{{range .Containers}}{{.Name}}{{"\n"}}{{end}}' 2>/dev/null
    docker ps -a --format '{{.Names}}' 2>/dev/null | grep -E \
      '^(gl0|gl1|ml0|cl1|dl1)-[0-9]+$|^(tx-sender|snapshot-streaming|snapshot-streaming-postgres)$'
  } | sed '/^$/d' | sort -u | while read -r name; do
    # Operator escape hatch, and a second line of defence for anything that ends
    # up on the harness network by accident.
    if [ "$(docker inspect -f '{{index .Config.Labels "tessellation.protect"}}' \
            "$name" 2>/dev/null)" = "true" ]; then
      echo "      (protected, skipping: $name)" >&2
      continue
    fi
    echo "$name"
  done
}

# --- volumes ----------------------------------------------------------------
#
#   gl0-data-N / gl1-data-N   docker-compose.volumes.yaml names these
#                             `gl0-data${CONTAINER_NAME_SUFFIX}`
#   ss-pgdata                 snapshot-streaming/docker-compose.yaml
#   {clickhouse,prometheus,grafana}-data
#                             docker/monitoring/docker-compose.local.yaml.
#                             Safe to match by name, unlike the containers: the
#                             nightly box prefixes its own with `monitoring-`.
harness_volumes() {
  docker volume ls -q 2>/dev/null | grep -E \
    '^(gl0|gl1)-data-[0-9]+$|^ss-pgdata$|^(clickhouse|prometheus|grafana)-data$'
}

# --- teardown ---------------------------------------------------------------
#
# Containers, then the network, then volumes. Logs the target list BEFORE acting:
# both hooks swallow every error and always exit 0, so without this a mis-scoped
# filter would leave no trace at all.
remove_harness_state() {
  local names vols

  names="$(harness_containers)"
  if [ -n "$names" ]; then
    echo "--- removing $(echo "$names" | wc -l | tr -d ' ') harness container(s) ---"
    echo "$names" | sed 's/^/      /'
    # `restart: unless-stopped` in docker-compose.yaml means a plain stop would
    # see them come straight back. rm -f is required.
    echo "$names" | xargs -r docker rm -f >/dev/null 2>&1 || true
  else
    echo "--- no harness containers present ---"
  fi

  # Fixed name on a fixed subnet, so it must be gone before the next job
  # recreates it (compose-runner.sh:175 fails against a stale one).
  #
  # Docker refuses to remove a network that still has attached endpoints, so a
  # container held back by `tessellation.protect=true` pins it permanently — and
  # the next job then dies in compose-runner.sh with no clue why. Report it;
  # silence here is how a one-off protect label turns into every later job
  # failing.
  if docker network inspect tessellation_common >/dev/null 2>&1; then
    if ! docker network rm tessellation_common >/dev/null 2>&1; then
      echo "::warning::could not remove the tessellation_common network; still attached:" \
           "$(docker network inspect tessellation_common \
                -f '{{range .Containers}}{{.Name}} {{end}}' 2>/dev/null)." \
           "The next job WILL fail to create it — detach or unprotect those containers."
    fi
  fi

  vols="$(harness_volumes)"
  if [ -n "$vols" ]; then
    echo "--- removing $(echo "$vols" | wc -l | tr -d ' ') harness volume(s) ---"
    echo "$vols" | sed 's/^/      /'
    echo "$vols" | xargs -r docker volume rm -f >/dev/null 2>&1 || true
  fi
}

# --- disk -------------------------------------------------------------------
#
# REPLACES `docker system prune -af --volumes`, which carried two separate
# hazards: `-a` evicts every image no container currently uses — on a shared host
# that includes the chain node image whenever the chain is stopped for a redeploy
# — and `--volumes` is what turned "containers removed" into "retained data
# gone".
#
# Dangling layers and the build cache belong to nobody, so they are always safe
# to drop. If that is not enough, say so loudly: a host that stays full is a real
# problem and should be visible, not papered over by deleting another tenant's
# data.
reclaim_disk() {
  local threshold="$1" used
  used=$(df --output=pcent / 2>/dev/null | tail -1 | tr -dc '0-9')
  [ -n "$used" ] || return 0
  echo "--- disk at ${used}% ---"
  [ "$used" -gt "$threshold" ] || return 0

  echo "--- above ${threshold}%, reclaiming (dangling images + build cache only) ---"
  docker image prune -f >/dev/null 2>&1 || true
  docker builder prune -f --keep-storage 10GB >/dev/null 2>&1 || true

  used=$(df --output=pcent / 2>/dev/null | tail -1 | tr -dc '0-9')
  echo "--- disk now ${used:-?}% ---"
  if [ -n "$used" ] && [ "$used" -gt "$threshold" ]; then
    echo "::warning::runner disk still ${used}% after reclaim — needs manual attention." \
         "Deliberately NOT running 'docker system prune -a --volumes': it would delete" \
         "images and volumes belonging to anything else sharing this host."
  fi
}

# --- workspace --------------------------------------------------------------
#
# Root-owned node data/logs the containers wrote into the workspace.
# actions/checkout cannot unlink these as the `runner` user, so leaving them
# fails the NEXT job before it starts. Deleted from inside a container, which
# runs as root — the same trick as the justfile's clean-data recipe.
clean_workspace_nodes() {
  [ -n "${GITHUB_WORKSPACE:-}" ] || return 0
  [ -d "${GITHUB_WORKSPACE}/nodes" ] || return 0
  echo "--- removing root-owned nodes/ from the workspace ---"
  docker run --rm -v "${GITHUB_WORKSPACE}/nodes:/nodes" alpine \
    sh -c 'rm -rf /nodes/* 2>/dev/null || true' >/dev/null 2>&1 || true
  rm -rf "${GITHUB_WORKSPACE}/nodes" 2>/dev/null || true
}
