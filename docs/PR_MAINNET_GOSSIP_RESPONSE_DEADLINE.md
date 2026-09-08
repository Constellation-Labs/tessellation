## Why I am submitting this

I reproduced a case in my isolated testing environment where faulty gossip
responses prevent otherwise healthy validators from completing timed snapshots.
The correction restores progress in that controlled failure case without changing
consensus voting, participant-selection rules, or the reward calculation.

This addresses an avoidable network-message delay. It is not a claim that every
Mainnet slowdown has this cause, that faulty participants can be detected instantly,
or that a particular daily reward rate is guaranteed.

## Problem reproduced

Stock v3.5.30 applies the five-second gossip timeout to acquiring an HTTP response.
It does not bound reading the response body afterward. A peer can return headers
promptly and then leave its body unfinished, keeping a gossip request worker busy.
Enough stalled responses prevent healthy peers' messages from being fetched, so
even the existing consensus recovery procedure cannot receive the inputs it needs.

These are concurrent HTTP request workers, not v4 rotating consensus seats.
Mainnet's default is eight peer-gossip workers. A real-runner component test fills
all eight and confirms that a ninth healthy peer is not queried during 180 seconds
of virtual time on stock. With the correction it is queried successfully within
the twelve-second observation window.

## What this changes

- Apply the existing client timeout to the entire recurring peer query, common
  query, and common offer, including response consumption and JSON decoding.
- Raise a timeout error and release the response resource when that deadline
  expires, allowing the existing worker cleanup and health-check path to run.
- Preserve one-time gossip initialization behavior. It does not occupy these
  recurring workers and is not part of this correction.

There is no new feature flag, retry, blacklist, activation ordinal, or configuration
value. Phase recovery timers, acknowledgment thresholds, membership rules, signed
schemas, reward formulas and supply limits are unchanged. The shared gossip client
is affected wherever used; the native comparison specifically exercises Global L0.

## How this was verified

The native comparison uses five isolated validators with throwaway identities.
Both stock and corrected runs use two peer-gossip workers so two faulty responders
can saturate the small test topology. The eight-worker default is tested separately
in the real-runner component test; it is not changed for Mainnet.

After five-validator agreement and two healthy rounds, private proxies withhold
the two responders' peer-query bodies for 210 seconds while returning their native
response identity/session headers. The proxies manufacture no rumor or signature.
The test measures accepted snapshot ordinals, timed epochs, rewards, and agreement
between the three unaffected observers—not merely HTTP error counts.

| During the 210-second fault | Stock v3.5.30 | Corrected |
| --- | ---: | ---: |
| Accepted timed epochs on the three healthy observers | 9 → 9 | 9 → 12 |
| Timed epoch advances | 0 | 3 |
| Affected round completes while fault remains active | No | Yes, in 65–68 seconds |
| Conflicting sampled snapshot values | 0 | 0 |

All three new corrected snapshots contained rewards. After fault removal, the
corrected run passed a separate three-round recovery gate: all three healthy
observers advanced from matching ordinal 12 to matching ordinal 15. Both trials
cleaned up their private containers and bridge while preserving evidence.

The initial candidate also demonstrated progress, but applied the deadline more
broadly to initialization. Review removed that unnecessary startup behavior change
and the native comparison was repeated on the narrowed binary reported above.

| Local validation | Passed |
| --- | ---: |
| Node Shared | 295 |
| Global L0 | 93 |
| Currency L0 | 41 |
| Shared | 132 |
| Scala total | 561 |
| Native-tooling unit tests | 28 |

There were zero failures and two existing ignored Global L0 tests. Assembly and
`scalafmtCheckAll` passed. The additional suites were rerun after narrowing the
patch. Local results are not a substitute for upstream CI or maintainer review.

## Safety

The deadline does not authorize local removal of a validator. Participant exclusion
still follows the existing consensus recovery rules. The configured 50-second
phase timeout and acknowledgment delay remain; the correction allows recovery
messages to be fetched instead of leaving workers occupied indefinitely.

No partially decoded rumor is emitted as valid. Fully decoded rumors already
emitted before a timeout still follow the unchanged validation path. A timed-out
query is not reported as a successfully completed stream and this patch adds no
replay or immediate retry. Cancellation releases the HTTP resource.

Tests cover deadline accounting, resource cleanup, cancellation, endless whitespace,
malformed JSON, invalid-session body isolation, healthy responses and unchanged
initialization body lifetime. Session responses are stubbed in the new component
fixtures; those fixtures do not independently prove cryptographic authentication.
The native runs retain the real validator protocol and do not bypass certificates,
signatures, version checks, or finality rules.

All runtime faults are confined to exact private Docker network namespaces.
No public validator, production key, public transaction or public network state
is used or modified.

## Base, compatibility, and limits

The intended upstream base is `release/mainnet` at
`9b1f826db65d56d1736a298fd18c842e0c93f5d6` (v3.5.30). September 8 checks found
that Mainnet and develop heads remained unchanged. Complete changed-file lists
for the nine open upstream PRs showed no direct GossipClient overlap. Develop
already has a per-peer failure cooldown; this patch does not copy it. A response
that never finishes produces no failure for that cooldown to count.

This is independent of the earlier transient-disconnect retry proposal. That
proposal is not a prerequisite and its retry code is not included here.

The demonstrated reward benefit is continued processing of reward-bearing timed
snapshots under this fault—not altered rewards, retroactive compensation, or proof
of the September Mainnet incident's cause. Full reentry of both fault targets,
production-scale throughput, and supported upgrade behavior are not established
by these small-topology runs. Existing version-hash checks also mean arbitrary
mixed binaries cannot be treated as a supported rolling upgrade.

Normal upstream CI, maintainer review and release coordination remain required.

## Supporting evidence

- [Implementation, tests and limitations](mainnet-gossip-response-deadline.md)
- [Measured results and artifact identities](validation/gossip-response-deadline/README.md)

The evidence preserves the initial collector coverage failure and recovery-gate
correction. Full raw logs, keystores and private runtime material are not published.
