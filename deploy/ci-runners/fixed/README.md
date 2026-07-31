# Fixed pool E2E runners (Hetzner Cloud, no autoscaler)

A fixed set of always-on Hetzner Cloud servers, each running one persistent
GitHub Actions runner registered under the label `tessellation-e2e`.

The alternative implementation of the same label is
[`../autoscaled`](../autoscaled) — see [`../README.md`](../README.md) for how to
choose.

## Why you might pick this one

- **No dedicated Hetzner project needed.** Nothing here enumerates or deletes
  servers it doesn't own, so it is safe to run inside the existing tessellation
  project alongside the `testnet-*` / `nightly*` clusters. Terraform state is
  separate from `deploy/terraform`, and every resource carries
  `component=ci-runners` labels with a distinct `ci-runner-*` name prefix.
- **No controller.** No always-on service that, if it dies, stops all CI *and*
  stops reaping billable servers.
- **Warm caches.** Docker layers and any sbt/coursier state persist between jobs,
  so there's no per-job boot or cold-cache cost.

## The honest trade-off

**Always-on cloud only saves money by accepting queueing.** Per-core price is
essentially flat across the CCX line (~€20/core/mo at every size), so there is no
economy of scale to exploit — buying full 9-way concurrency means buying 9 boxes.

One runner per server (see [below](#why-one-runner-per-server)), so
**concurrency = `runner_count`** and the 9-job matrix runs in
`ceil(9 / runner_count)` waves. Against the ~$2,000/mo (≈€1,852 at ~1.08 USD/EUR)
GitHub baseline, at `hel1` list prices:

| Runners × type | Concurrent | Waves | PR wall-clock | €/mo | Saving |
|---|---|---|---|---|---|
| 3× `ccx33` | 3 | 3 | ~75 min | 489 | 74% |
| 4× `ccx33` | 4 | 3 | ~75 min | 652 | 65% |
| **3× `ccx43`** | **3** | **3** | **~75 min** | **976** | **47%** |
| 5× `ccx43` | 5 | 2 | ~50 min | 1,627 | 12% |
| 9× `ccx43` | 9 | 1 | ~25 min | 2,929 | **−58%** |

Current PR feedback is ~25 min (all 9 jobs in one wave). The default here — 3×
`ccx43` — trades that for ~75 min to save ~47%.

If you want savings **and** current wall-clock, this variant can't give you both;
use [`../autoscaled`](../autoscaled) (~76%, elastic) instead.

## Why one runner per server

The E2E harness is not multi-tenant. `docker/bin/compose-runner.sh:174` creates a
fixed-name docker network `tessellation_common` on a fixed subnet (`NET_PREFIX`,
default `172.32.0.0/24`), with fixed container names (`gl0-0`, `gl1-0`, …) and
fixed host ports (9000–9412). Two concurrent jobs on one host collide on all four.

Packing more runners per box would need either a Docker daemon per runner in its
own network namespace (privileged DinD), or parameterizing the harness's network
name and container-name suffix. Both are real options for improving €/concurrency
— and both are extra moving parts in a suite that
`docker/docker-compose.test.yaml` already documents as timing-fragile under
multi-JVM contention. Neither is done here.

## Deploy

```sh
cd deploy/ci-runners/fixed/terraform

export TF_VAR_hcloud_token=...                        # existing project token is fine
export TF_VAR_team_ssh_keys='["ssh-ed25519 AAAA..."]'
export TF_VAR_allowed_ssh_cidrs='["203.0.113.0/24"]'  # fail-closed: empty = no SSH
# optional: export TF_VAR_runner_count=3 TF_VAR_runner_server_type=ccx43

terraform init
terraform apply
```

Then install and register the runners. GitHub requires proof of authorization to
join a machine to the repo, but **no long-lived credential is needed** — pick
either path.

### Option A — no PAT (UI-copied registration tokens)

Use this when org policy blocks classic PATs, SAML SSO is in the way, or you'd
simply rather not mint one.

In the repo UI, go to **Settings → Actions → Runners → New self-hosted runner →
Linux**. The displayed `./config.sh --token AXXXX...` command contains a
registration token. Copy the value after `--token`. **Repeat once per runner** —
each token is single-use.

```sh
export GITHUB_REPOSITORY=Constellation-Labs/tessellation
export REG_TOKENS="AXXXX...,BYYYY...,CZZZZ..."   # one per runner, in IP order

deploy/ci-runners/fixed/register-runners.sh \
  "$(terraform -chdir=deploy/ci-runners/fixed/terraform output -raw runner_ips_csv)"
```

Those tokens expire in ~1 hour, are single-use, and grant nothing beyond "join
this repository" — so there's no lasting credential to store or rotate. The
script validates the token count against the host count before touching any
server.

### Option B — classic PAT

More convenient for repeat runs, since the tokens are minted automatically.

```sh
export GITHUB_REPOSITORY=Constellation-Labs/tessellation
export GITHUB_TOKEN=...          # classic PAT, `repo` scope

deploy/ci-runners/fixed/register-runners.sh \
  "$(terraform -chdir=deploy/ci-runners/fixed/terraform output -raw runner_ips_csv)"
```

The PAT stays on your machine — it's used only to mint the same short-lived
per-host registration tokens. The PAT itself never reaches the runners. If the
org enforces SAML SSO, authorize the token for it first (**Configure SSO** next
to the token in the Tokens (classic) list), or API calls return a confusing 404.

`REG_TOKENS` takes precedence over `GITHUB_TOKEN` if both are set.

---

`register-runners.sh` is idempotent: re-run it to roll out a hook change, bump
`RUNNER_VERSION`, or re-register. An existing runner is stopped and its *local*
config removed (`config.sh remove --local`, so the single-use registration token
isn't consumed), then re-registered with `--replace` under the same name — so
runners are replaced, never duplicated.

Verify at `https://github.com/<repo>/settings/actions/runners` — you should see
`ci-runner-1..N` idle with the `tessellation-e2e` label.

## Operations

```sh
# Runner service status / logs
ssh admin@<runner-ip> 'sudo /home/runner/actions-runner/svc.sh status'
ssh admin@<runner-ip> 'sudo journalctl -u "actions.runner.*" -f'

# What's running right now
ssh admin@<runner-ip> 'docker ps && df -h / && free -m'
```

**Scale the pool:** change `TF_VAR_runner_count`, `terraform apply`, then re-run
`register-runners.sh` with the new IP list. Scaling *down* — unregister the
doomed runner first so GitHub doesn't queue jobs onto a dead box:

```sh
ssh admin@<runner-ip> 'cd /home/runner/actions-runner && sudo ./svc.sh stop && sudo ./svc.sh uninstall'
# then reduce runner_count and apply
```

**Resize the boxes:** changing `runner_server_type` replaces the servers, so
re-run `register-runners.sh` afterwards.

**Rollback to GitHub-hosted runners:** set the repository variable
`E2E_RUNNER_LABEL` to `Ubuntu-22-64-core`. Effective on the next job, no PR.

## State hygiene

Persistent runners accumulate state, which is the main operational risk here (the
autoscaled variant gets a fresh box per job and has no equivalent problem). Two
hooks, installed by `register-runners.sh` and wired via the runner's `.env`,
handle it:

- **`hooks/job-started.sh`** — defensive pre-flight, for when a job dies hard and
  skips its completion hook: remove stale containers, drop a leftover
  `tessellation_common` network, delete root-owned `nodes/` from the workspace,
  and prune if disk is over 70%.
- **`hooks/job-completed.sh`** — teardown: force-remove all containers (the
  compose files set `restart: unless-stopped`, so a plain stop isn't enough), drop
  the network, prune volumes and dangling images, and full-prune above 80% disk.

Both run *after* the workflow's own log-collection steps, so diagnostics are
preserved. Both always `exit 0` — a cleanup error must never turn a green run red.

The root-owned-`nodes/` cleanup matters more than it looks: containers write node
data as root into the workspace, and `actions/checkout` can't unlink it as the
`runner` user, so without this a job fails before it starts. The hooks delete it
from inside a container, the same trick as the `clean-data` recipe in the
`justfile`.

## Right-sizing

`ccx43` is **inferred** from ~13 JVM containers at default `-Xmx8g` /
`-XX:ActiveProcessorCount=8`, not measured. Measure during a real job:

```sh
ssh admin@<runner-ip> 'docker stats --no-stream; free -m; uptime; nproc'
```

- Peak RSS well under ~28 GB and load below core count → `ccx33` takes 3 runners
  from €976 to €489/mo.
- New consensus timeouts / `NoProgress` churn that don't happen on GitHub → step
  **up** to `ccx53` before touching timeouts. A `ccx43` has ~4× less CPU than the
  64-core baseline, the likeliest cause of new timing flakes.

## Known risks

- **A wedged runner silently swallows jobs.** GitHub will keep assigning to a
  registered-but-broken runner until the workflow times out. Check the runners
  page if E2E jobs hang without logs.
- **Disk exhaustion** is the classic persistent-runner failure and shows up as
  weird mid-test errors, not clean "no space" messages. The hooks guard at 70/80%;
  `ccx43` ships 360 GB.
- **Idle capacity is pure waste** — real demand is ~198 job-hours/month against
  2,190 host-hours for a 3-box pool (~9% duty cycle). That's the structural
  argument for the autoscaled variant.
