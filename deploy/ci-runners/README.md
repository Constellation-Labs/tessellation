# Tessellation E2E CI Runners (Hetzner Cloud)

Self-hosted GitHub Actions runners for the E2E matrix in
[`.github/workflows/e2e-just-test.yml`](../../.github/workflows/e2e-just-test.yml),
replacing the GitHub-hosted `Ubuntu-22-64-core` larger runner
(**$0.162/min ≈ $2,000/mo** for 9 jobs per PR run).

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
always-on cloud **only saves money by accepting queueing**. At full 9-way
concurrency it costs ~58% *more* than GitHub (measured job durations make 3
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
`-XX:ActiveProcessorCount=8`. The whole 9-group matrix is **~70 min of work**.

Validated end-to-end twice on a single `ccx33` runner:

- **fork, 2026-07-31 → 08-03** — all 11 jobs passed; peak 26.7 GB, load 15.42
- **`Constellation-Labs/tessellation`, 2026-08-21** — **all 12 jobs passed**
  (10 E2E groups on Hetzner, ~66 min sequential); peak **29.7 GB (95%)**, load
  12.01, 10% of active samples above 90% memory

The second run passed `allow-spends` and `spend` — the two that had failed
earlier — which shows the `ccx33` is *marginal* rather than broken. It is still
rejected: a fleet that clears 95% memory on luck is a flake source.

| | `ccx33` (8c/32 GB) | **`ccx43` (16c/64 GB)** | `ccx53` (32c/128 GB) |
|---|---|---|---|
| €/h · €/mo | 0.2612 · 162.99 | 0.5216 · 325.49 | 1.0088 · 629.49 |
| autoscaled | ~€195 (89%) | **~€353 (81%)** | ~€683 (63%) |
| fixed, 3 runners | €489 (74%) | **€976 (47%)** | €1,888 (−2%) |
| verdict | **REJECTED** | **recommended** | more margin, half the saving |

**`ccx33` is rejected on evidence, not caution.** It passed once, then failed
twice in two distinct ways on repeat runs:

- **Memory** — p90 99%, peak 100% of 32 GB, with no swap at the time. The kernel
  OOM-killed the Actions runner agent itself during `allow-spends`; the unit went
  `failed` and the remaining 8 jobs queued forever. Cloud-init now provisions a
  16 GB swapfile so this degrades to paging instead of a kill — but a box that
  needs swap to survive is still undersized.
- **CPU** — peak load 15.42/8 cores (193%); 16% of samples over 1.0/core. GL0's
  `/global-snapshots/latest/combined` returned **HTTP 503** under the contention and
  `spend` failed.

On `ccx43` those peaks become ~42% memory and ~96% load, with only 0.7% of samples
above load 16. Memory is the harder constraint, which is why the runners now carry
a 16 GB swap backstop (`vm.swappiness=10`) — it converts an OOM kill into a slow
job, without making a smaller box the right choice.

Prices are `hel1`, from the Hetzner Cloud API for this account (net == gross). CCX
(dedicated vCPU) throughout — **not** CPX (shared); see the timing fragility note
above.

> **Quota:** `ccx43` is 16 dedicated cores. A default Hetzner project allows 8, so
> creating one returns `resource_limit_exceeded` until you request an increase.

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
- **The `build` job** and the `snapshot-streaming` E2E group — both stay on
  GitHub-hosted `ubuntu-22.04` runners, which are cheap and unaffected by this
  migration.
