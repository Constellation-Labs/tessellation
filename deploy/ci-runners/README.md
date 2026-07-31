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
| PR wall-clock | ~25 min (unchanged) | ~75 min at 3 runners (3 waves) |
| **Est. cost** | **~€437/mo (76% saving)** | **~€976/mo (47% saving)** |
| Dedicated Hetzner project | **required** | not required |
| Credentials | new HC project token + classic PAT | existing HC token; **no PAT needed** (UI-copied registration tokens work) |
| Extra moving parts | controller service (SPOF) | none |
| State between jobs | none (fresh box) | persists — needs cleanup hooks |
| Caches | cold each job | warm |

## Which to use

**`autoscaled/` is the better answer on cost and speed** — it keeps today's ~25
min PR feedback *and* saves ~76%, because CI duty cycle is only ~9% (~198
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
concurrency it costs ~58% *more* than GitHub. See
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

## Sizing (applies to both)

A job runs ~13 JVM containers (3 `gl0` + 3 `gl1` + 1 `ml0` + 3 `cl1` + 3 `dl1`),
each defaulting to `-Xmx8g` with `-XX:ActiveProcessorCount=8`.

`ccx43` (16c / 64 GB) is the default in both variants and is **inferred from that,
not measured.** It is the dominant cost variable in both:

| | `ccx33` (8c/32 GB) | `ccx43` (16c/64 GB) | `ccx53` (32c/128 GB) |
|---|---|---|---|
| €/h · €/mo | 0.2612 · 162.99 | 0.5216 · 325.49 | 1.0088 · 629.49 |
| autoscaled est. | ~€230 (88%) | ~€437 (76%) | ~€824 (56%) |
| fixed, 3 runners | €489 (74%) | €976 (47%) | €1,888 (−2%) |

Measure peak RSS and load on the first green run before settling — the difference
between `ccx33` and `ccx43` is roughly half the bill. Conversely, if E2E starts
flaking on consensus timing only on Hetzner, step **up** before tuning timeouts: a
`ccx43` has ~4× less CPU than the 64-core baseline, which is the likeliest cause.

Prices are `hel1`, pulled from the Hetzner Cloud API for this account (net ==
gross). CCX (dedicated vCPU) throughout — **not** CPX (shared); see the timing
fragility note above.

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
