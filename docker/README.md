# Local Docker and E2E workflow

This is the canonical operator guide for the repository's local `just`/Docker E2E
infrastructure. Keep it aligned with the executable sources below whenever their behavior changes:

- [`../justfile`](../justfile) — public commands;
- [`bin/set-env.sh`](bin/set-env.sh) — supported flags and defaults;
- [`bin/compose-runner.sh`](bin/compose-runner.sh) — build, startup, test, and exit lifecycle;
- [`bin/assembly.sh`](bin/assembly.sh) — assembly and staged-JAR behavior; and
- [`bin/tessellation-docker-cleanup.sh`](bin/tessellation-docker-cleanup.sh) — scoped Docker cleanup.

For diagnosing a cluster while it is still running, use
[`../docs/operations/local-e2e-cluster-investigation.md`](../docs/operations/local-e2e-cluster-investigation.md).
Claude agents should also use [`../.claude/commands/debug-e2e-logs.md`](../.claude/commands/debug-e2e-logs.md)
for the per-node persisted-log procedure. Do not create a parallel E2E harness when an existing
`just` scenario can exercise the behavior.

## Common commands

```bash
just --list
just test --list-tests

# Build the requested assemblies, start the cluster, and run one scenario.
just test --test=dag-cluster

# Reuse the exact JARs currently staged in docker/jars/. See the provenance warning below.
just test --skip-assembly --test=dag-cluster

# Start a cluster without running the JS/shell E2E scenarios.
just up

# Build/stage the node JARs and Docker image without starting containers.
# --skip-streaming avoids the separate Snapshot Streaming build.
just build --skip-streaming

# Remove the local containers after a test exits. Host-mounted nodes/ logs and data remain.
just test --test=dag-cluster --cleanup

# Stop and remove the scoped Tessellation Docker resources after inspection.
just down
```

Bare `just test` passes `--use-test-metagraph` and runs every registered scenario, including
Snapshot Streaming unless `--skip-streaming` is supplied. Prefer an explicit `--test=<name>` while
iterating.

The supported automatic teardown option is `--cleanup`. The formerly documented
`--cleanup-docker-at-end` option does not exist.

`--clean-assembly` is currently parsed by `set-env.sh` but not consumed by the assembly runner. It
does not run `sbt clean` and must not be relied upon. Use the explicit cleanup procedure below.

## Lifecycle and evidence preservation

A local `just test` or `just up` performs these operations before starting the cluster:

1. Starts scoped cleanup of containers named `gl0-*`, `gl1-*`, `ml0-*`, `cl1-*`, `dl1-*`,
   Snapshot Streaming, their named volumes, and `tessellation_common`.
2. Builds or reuses assemblies, copies them into `docker/jars/`, and builds
   `constellationnetwork/tessellation:test`.
3. By default, deletes and recreates the repository-root `nodes/` tree before generating keys,
   environment files, and Compose files.
4. Starts services and runs the selected scenario.

Consequences:

- A failed test normally leaves the cluster running and queryable. Inspect it before teardown.
- Starting the next ordinary run destroys the previous run's `nodes/` logs and data.
- `--cleanup` and `just down` remove containers, so capture `docker logs` first if those streams are
  needed. The host-mounted application logs under `nodes/` remain until a later run or explicit
  node cleanup.
- `just down --clean` has no additional effect for a local cluster. The `--clean` switch is only
  consumed by remote teardown.

Before cleanup, record the run identity:

```bash
git rev-parse HEAD
sha256sum docker/jars/gl0.jar
unzip -p docker/jars/gl0.jar META-INF/MANIFEST.MF \
  | grep -E '^(Implementation|Specification)-Version:'
for n in nodes/*/peer_id; do printf '%s ' "$n"; head -c 16 "$n"; echo; done
```

Peer IDs are key-derived and must be mapped from `nodes/<index>/peer_id` for the specific run. Do
not reuse a node-to-peer map from earlier evidence.

## Output locations

| Output | Local path | Lifetime |
|---|---|---|
| GL0 application logs | `nodes/<i>/gl0-logs/` | Survive container removal; removed by the next ordinary run, `just clean-data`, or `just clean-configs` |
| Other layer logs | `nodes/<i>/{gl1,ml0,cl1,dl1}-logs/` | Same |
| Node state | `nodes/<i>/{gl0,gl1,ml0,cl1,dl1}-data/` | Same |
| Run keys/config/identity | `nodes/<i>/{.env,peer_id,address,key.p12,...}` | Removed by the next ordinary run or `just clean-configs` |
| SBT assembly outputs | `modules/<module>/target/scala-2.13/` | Removed by `sbt clean`/`just clean` |
| Test-metagraph build outputs | `.github/templates/metagraphs/project_template/**/target/` | Removed by `just clean` |
| JARs staged for Docker | `docker/jars/` | Replaced by a non-skipped assembly; **not** removed by `just clean` |
| Local node image | `constellationnetwork/tessellation:test` | **Not** removed by `just clean` or `just clean-docker` |
| JS dependencies | `.github/action_scripts/node_modules/` | Recreated by `npm ci`; not removed by `just clean` |
| Snapshot Streaming build | `docker/snapshot-streaming/{.build,snapshot-streaming.jar,block-explorer,data}/` | Partially reused; not fully removed by `just clean` |
| Locally published SDK | `~/.ivy2/local/io.constellationnetwork/tessellation-sdk_2.13/<exact-version>/` | Versioned shared cache; not removed by `just clean` |

Container stdout/stderr is separate from the bind-mounted logs and is available through
`docker logs <container>` only while that container still exists.

### CI and scenario-specific evidence

GitHub's E2E workflow creates `runner-logs/`, captures container status, `docker logs`, and copies of
the node log directories, then uploads an `e2e-<scenario>-logs-<run>` artifact. That directory is CI
staging, not the normal local log location.

The `rollback-download-head` scenario writes a self-contained local bundle to
`${ROLLBACK_DOWNLOAD_EVIDENCE_ROOT:-${TMPDIR:-/tmp}/tessellation-e2e-evidence}/rollback-download-head-<UTC timestamp>/`.
Other scenario scripts must document any nonstandard evidence root in their header. Long-term,
deliberately retained investigation evidence may be copied into the relevant `.workspace/<case>/`
tree with commit/JAR/run provenance; Just does not do that automatically.

## Staged-JAR provenance

`--skip-assembly` trusts whatever nonempty JARs are already in `docker/jars/`. It does not compare
their manifest version or digest with the checked-out commit. Before using it, verify the manifest
and SHA-256 as shown above. If provenance is unknown or does not match the intended commit, rebuild
without `--skip-assembly`.

There are three distinct copies of locally built node code:

1. assembly JARs under each module's `target/`;
2. renamed copies under `docker/jars/`; and
3. those staged copies embedded in `constellationnetwork/tessellation:test`.

Cleaning only one layer does not produce a clean-room run.

## Cleanup matrix

Never remove bind-mounted node data while the containers are running. Start with `just down`.

| Command | Removes | Preserves |
|---|---|---|
| `just down` | Scoped Tessellation containers, named volumes, Snapshot Streaming data, and `tessellation_common` | `nodes/`, SBT targets, `docker/jars/`, image |
| `just clean-data` | Layer `*-data/` and `*-logs/` under `nodes/` and legacy `docker/nodes/` | Keys, peer IDs, `.env`, Compose files, JARs |
| `just clean-configs` | Entire `nodes/` and legacy `docker/nodes/`, then recreates empty `nodes/` | Build outputs, staged JARs, image |
| `just clean-docker` | Same scoped Docker resources as `just down` | Node bind mounts, build outputs, staged JARs, image |
| `just clean` | Main-repo and test-metagraph SBT targets, generated node tree, scoped Docker resources | `docker/jars/`, image, Snapshot Streaming build/JAR/clone, JS dependencies, local Ivy publications |
| `just purge-docker` | **All** containers, volumes, and networks on the host | Not scoped to Tessellation; do not use on a shared workstation |

Because `just clean-data` and `just clean-configs` do not stop live containers, never invoke them
before `just down`. `just clean` currently calls node cleanup before its Docker cleanup, so the safe
full sequence also starts with `just down`.

### Clean-room core E2E build

After preserving any needed evidence:

```bash
just down
just clean
rm -f -- docker/jars/*.jar
docker image rm constellationnetwork/tessellation:test 2>/dev/null || true
```

The next run must omit `--skip-assembly`.

### Additional Snapshot Streaming cleanup

```bash
rm -f -- docker/snapshot-streaming/*.jar
rm -f -- docker/snapshot-streaming/application.conf
rm -rf -- docker/snapshot-streaming/.build
rm -rf -- docker/snapshot-streaming/block-explorer
rm -rf -- docker/snapshot-streaming/data
```

Successful Snapshot Streaming builds normally delete `.build`; an interrupted build may leave it.

### External metagraph and `publishLocal` cleanup

`just clean` cleans the in-repository test-metagraph template, not a metagraph supplied through
`--metagraph=/path`. Clean an external metagraph in its own repository:

```bash
(cd /path/to/metagraph && sbt clean)
```

Metagraph and Snapshot Streaming builds may run `sdk/publishLocal`. That publishes a versioned SDK
under `~/.ivy2/local/io.constellationnetwork/tessellation-sdk_2.13/`. Do not delete the whole Ivy or
Coursier cache: other worktrees and metagraphs can depend on it. If removal is required, first
record `TESSELLATION_VERSION` from runner output and delete only that exact version directory.

## Metagraph and release-oriented builds

Use the in-repository template metagraph and run its registered scenarios:

```bash
just test --use-test-metagraph --test=currency
```

Use an external metagraph repository:

```bash
just test --metagraph=/path/to/metagraph --test=currency
```

By default the runner assembles ML0. Add `--cl1` or `--dl1` for those layers. When the metagraph
must compile against changes in this Tessellation worktree, `--publish` publishes this checkout's
SDK under the derived `TESSELLATION_VERSION` before assembling the metagraph. If both repositories'
JARs were built deliberately in advance, they can be reused with:

```bash
just test --skip-assembly \
  --metagraph=/path/to/metagraph \
  --skip-metagraph-assembly \
  --test=currency
```

Verify both the staged Tessellation and metagraph JAR provenance before using that fast path.

`--l1` includes DAG L1 in the Tessellation assembly selection. A version can be supplied explicitly
for local build metadata with, for example, `just build --skip-streaming --version=v4.1.0-rc.14`.

## Remote debug helper

`just debug-main` builds from the current branch, copies a debug GL0 deployment to configured SSH
hosts, and tails its logs. It is intended for disposable diagnostic nodes, not ordinary local E2E
or coordinated public-network release deployment. It requires `rsync` and SSH aliases similar to:

```sshconfig
Host genesis
  HostName <mainnet-source-ip>
  User admin

Host dest
  HostName <debug-node-ip>
  User root
```

The remote environment must supply the appropriate keystore and credentials. Review
`docker/bin/debug/mn-replicate.sh` before use; it performs remote mutations and is outside the local
cleanup contract documented above.

## Local topology

Each node index uses host ports `<prefix><index>0` for public HTTP and `+2` for CLI/join:

| Layer | Prefix | Example for index 0 |
|---|---:|---:|
| GL0 | 90 | 9000 / 9002 |
| GL1 | 91 | 9100 / 9102 |
| ML0 | 92 | 9200 / 9202 |
| CL1 | 93 | 9300 / 9302 |
| DL1 | 94 | 9400 / 9402 |

The default bridge is `tessellation_common` on `172.32.0.0/24`. Each node directory receives its
own Compose configuration, environment, key, and bind-mounted log/data directories.
