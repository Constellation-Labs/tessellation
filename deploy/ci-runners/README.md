# Tessellation E2E CI Runners (Hetzner Cloud)

Self-hosted GitHub Actions runners for the E2E matrix in
[`.github/workflows/e2e-just-test.yml`](../../.github/workflows/e2e-just-test.yml),
replacing the GitHub-hosted `Ubuntu-22-64-core` larger runner
(**$0.162/min ≈ $2,000/mo**). That baseline was measured when the matrix was 9
groups; it is **11** today, so every saving quoted here is conservative.

Two interchangeable implementations ship here. **Both register runners under the
same label, `tessellation-e2e`**, so the workflow does not care which one is
serving — and you can run both at once (the fixed pool as warm baseline, the
autoscaler for burst) without touching CI config.

| | [`autoscaled/`](autoscaled) | [`fixed/`](fixed) |
|---|---|---|
| Shape | one ephemeral server per job | N always-on servers, 1 runner each |
| Concurrency | elastic (up to `max_runners`) | `runner_count`, hard |
| PR wall-clock | ~25 min (unchanged) | ~23 min at 3 runners |
| **Est. cost** | **~€353/mo (81% saving)** | **~€976/mo (47% saving)** |
| Dedicated Hetzner project | **required** | not required |
| Credentials | new HC project token + classic PAT | existing HC token; **no PAT needed** (UI-copied registration tokens work) |
| Extra moving parts | controller service (SPOF) | none |
| State between jobs | none (fresh box) | persists — needs cleanup hooks |
| Caches | cold each job | warm |

## Which to use

**`autoscaled/` is the better answer on cost and speed** — it keeps today's ~25
min PR feedback *and* saves ~81%, because CI duty cycle is only ~9% (~198
job-hours/month of real demand). It also gets per-job isolation for free.

**`fixed/` exists because `autoscaled/` needs a dedicated Hetzner Cloud project.**
The autoscaler enumerates servers in its token's project and deletes any it finds
powered off, so it must never share a project with the `testnet-*` / `nightly*`
chain nodes — and Hetzner projects can only be created in the Cloud Console, not
via API. If you can't create that project yet, `fixed/` deploys into the existing
project today and still saves ~47%.

`fixed/` also needs **no new credentials**: the existing Hetzner token works, and
runners can be registered with single-use registration tokens copied from the repo
UI, so no classic PAT has to be minted at all. See
[`fixed/README.md`](fixed/README.md#option-a--no-pat-ui-copied-registration-tokens).

The honest limitation of `fixed/`: per-core price is flat across the CCX line, so
always-on cloud **only saves money by accepting queueing**. At full 11-way
concurrency it costs ~93% *more* than GitHub (measured job durations make 4
runners roughly match today's wall-clock, which is better than first estimated). See
[`fixed/README.md`](fixed/README.md#the-honest-trade-off) for the full table.

A third option, not implemented here: Hetzner **bare metal** (AX162 — 48 cores for
€199/mo, ~5× better €/core than CCX) would make an always-on pool genuinely cheap,
but it's ordered manually through Robot with no Terraform provider that can order
servers. Worth revisiting if the fixed pool proves out and you want it cheaper.

## Why one job per server (both variants)

The E2E harness is **not multi-tenant**. `docker/bin/compose-runner.sh:174`
creates a fixed-name docker network `tessellation_common` on a fixed subnet
(`NET_PREFIX`, default `172.32.0.0/24`), with fixed container names (`gl0-0`,
`gl1-0`, …) and fixed host ports (9000–9412). Two concurrent jobs on one host
collide on all four.

One job per server gets that isolation for free — **no docker-in-docker, and no
changes to the test harness.** It also avoids deliberately co-tenanting a suite
that is already timing-fragile: `docker/docker-compose.test.yaml` carries ~40 lines
documenting how multi-JVM CPU contention produces multi-second GC pauses, spurious
chronic-non-signer classification, and the "wedge profile" fork-recovery flake.

## Sizing (applies to both) — MEASURED

A job runs up to **15** containers (3 `gl0` + 3 `gl1` + 1 `ml0` + 3 `cl1` +
3 `dl1` + support), each JVM defaulting to `-Xmx8g` with
`-XX:ActiveProcessorCount=8`. The matrix is **11 groups**, all on the
`tessellation-e2e` label.

### Per-group peak RSS on a 32 GB box (2026-09-17)

Sampled every 30 s across a **12/12 green** matrix on the on-demand `cpx62`
fleet. This is the number that should drive sizing; everything before it was
inferred from container count and heap defaults.

| group | peak RSS | of 31.3 GB | swap | wall clock |
|---|---|---|---|---|
| `allow-spends` | 23,998 MB | **77%** | 0 | 24.3 min |
| `spend` | 23,969 MB | **77%** | 0 | 20.1 min |
| `currency` | 21,511 MB | 69% | 0 | 10.3 min |
| `token-locks` | 20,328 MB | 65% | 0 | 9.1 min |
| `rewards` | 12,501 MB | 40% | 0 | 5.5 min |
| `committee-rewards` | 10,198 MB | 33% | 0 | 15.2 min |
| `token-lock-replacement` | 8,018 MB | 26% | 0 | 9.1 min |
| `dag-cluster` | 7,420 MB | 24% | 0 | 7.0 min |
| `delegated-staking` | 7,341 MB | 23% | 0 | 8.0 min |
| `snapshot-streaming` | 5,869 MB | 19% | 0 | 6.2 min |
| `data-with-fee` | — | — | — | 6.2 min |

**The ceiling is 24 GB (77%), and swap was never touched by any group.** Total
work is 121 min; makespan on 8 runners ~24 min, against ~105 min serial on one.

Two things follow. **32 GB is sufficient with ~7 GB spare**, so the 16 GB
swapfile is insurance rather than something the box runs on. And **container
count does not predict memory**: `committee-rewards` runs the widest topology
(5 `gl0` + 3 `gl1`) at only 33%, while the nominally ordinary `allow-spends` and
`spend` are the heaviest. The old `ccx43` default was inferred from container
count in exactly that way.

CPU is not a constraint either: sampled load **2.74 on 16 cores (17%)** mid-`spend`,
and **0.0000% steal** on shared vCPU under real E2E load.

### History — why this supersedes the earlier figures

Validated twice on a single `ccx33` before the fleet existed:

- **fork, 2026-07-31 → 08-03** — all 11 jobs passed; peak 26.7 GB, load 15.42
- **`Constellation-Labs/tessellation`, 2026-08-21** — all 12 jobs passed
  (10 E2E groups, ~66 min sequential); peak **29.7 GB (95%)**, load 12.01

Those runs rejected `ccx33` on two grounds, and `ccx43` (16c/**64 GB**) was
recommended as the floor:

- **Memory** — p90 99%, peak 100% of 32 GB, **no swap at the time**. The kernel
  OOM-killed the Actions runner agent during `allow-spends`; the unit went
  `failed` and the remaining 8 jobs queued forever.
- **CPU** — peak load 15.42/8 cores (193%); GL0's
  `/global-snapshots/latest/combined` returned **HTTP 503** under contention and
  `spend` failed.

The 2026-09-17 data does not reproduce the memory half. `allow-spends` peaks at
24 GB, not 29.7 GB, and nothing came near 90%. The likely difference is that
those runs had **no swapfile**, so the kernel had nowhere to go and the reported
"peak" reflects a box already in trouble; the 15-container topology and heap
defaults are otherwise unchanged. Run-to-run variance is ~10% (run 1 of the same
matrix put `spend` at 26.4 GB / 84%), which does not span the gap on its own.

The CPU half was real and is simply fixed by core count: 16 cores puts the same
load at ~96% rather than 193%.

### Server types

| | `cpx62` (16c/32 GB) | `ccx33` (8c/32 GB) | `ccx43` (16c/64 GB) |
|---|---|---|---|
| vCPU | **shared** | dedicated | dedicated |
| €/h · €/mo | **0.2452 · 152.99** | 0.2612 · 162.99 | 0.5216 · 325.49 |
| autoscaled, 11 groups | **~€220 (88%)** | ~€233 (87%) | ~€443 (76%) |
| verdict | **recommended — measured** | superseded | over-provisioned |

**`cpx62` is the recommendation, on measurement.** It carries the whole matrix at
77% peak memory with swap untouched, and 17% CPU. `ccx43`'s 64 GB was sized for a
29.7 GB peak that does not reproduce, at twice the price. `ccx33` is the same
32 GB but half the cores, which is the half that genuinely failed.

Savings are against the ~$2,000/mo (~€1,852) `Ubuntu-22-64-core` baseline, itself
measured when the matrix was 9 groups, so they are conservative. Prices are
`hel1` from the Hetzner Cloud API for this account (net == gross).

> **Quota:** every `ccx*` row above is **unbuyable in this account**. The
> dedicated-core limit is 8 and `ci-runner-1` consumes all of it — a real
> `POST /servers` for `ccx33` *and* for `ccx13` (2 cores) both return HTTP 403
> `resource_limit_exceeded`. Deleting shared servers frees none of that quota.
> `cpx62` is shared vCPU and needs no increase.

## Rollback

`runs-on` reads `vars.E2E_RUNNER_LABEL` first, so setting that repository variable
to `Ubuntu-22-64-core` moves all E2E back to GitHub-hosted runners on the next
job — no PR, no rerun of in-flight work. Unset it to return to Hetzner. Worth
setting up before the first live run either way.

## Not managed here

- **The hypergraph cluster** (`testnet-*`, `nightly*`) — separate Terraform stack
  at [`deploy/terraform`](../terraform), separate state. Both stacks here use
  their own state keys (`ci-runners-autoscaled/`, `ci-runners-fixed/`) so nothing
  collides.
- **The `build` job** — stays on a GitHub-hosted `ubuntu-22.04` runner, which is
  cheap and unaffected by this migration. The `snapshot-streaming` E2E group used
  to as well, but it carries no `runner:` override in the workflow, so it now
  lands on `tessellation-e2e` like every other group.
