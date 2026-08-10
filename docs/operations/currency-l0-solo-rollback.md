# Currency L0 single-node rollback recovery

`currency-l0 run-rollback --allow-solo-consensus` is an opt-in recovery escape
hatch for a fully stopped metagraph. It lets exactly one rollback node seed the
next Currency L0 consensus outcome with itself instead of inheriting every proof
signer from the checkpoint.

This is an operational override, not a normal startup mode. Running it on two
isolated nodes can produce two valid but conflicting histories.

> **DANGER — never persist this flag.** Do not add
> `--allow-solo-consensus` to a systemd unit, container entrypoint, deployment
> manifest, environment variable, or monitoring/automatic-restart command. It
> is authorized for one manually coordinated rollback invocation only. An
> automatic restart that repeats the override while nodes are isolated can
> create a competing history. Disable automatic restarts before recovery and
> restore the normal command, without the flag, before re-enabling them.

## Why it is needed

Normal Currency L0 rollback derives `Facilitators` and `EligibleFacilitators`
from the rolled-back snapshot's proof signers. That is the safe default while a
quorum of those signers is available. When the entire metagraph stops, however,
a single restarted signer inherits a multi-peer committee and cannot finalize
the next ordinal.

A normal validator cannot break that cycle on a stopped chain. Currency L0's
download program observes through `tip + 4` before it starts facilitating
(`snapshot/programs/Download.scala`, `observationOffset` and `observe`). Those
four snapshots do not exist until the inherited committee makes progress, while
the inherited committee is waiting for the joining validator.

The override breaks only that cycle:

1. The selected node completes the ordinary rollback and initializes its
   consensus outcome with `{self}`.
2. A singleton Currency L0 committee has a unanimity quorum of one. The stall
   infeasibility gate also deliberately applies only when Core has at least two
   members (`StallDetector.readyParticipationStatus`).
3. The node produces the snapshots required by a joining validator's four-
   ordinal observation window.
4. Returning validators join normally and re-enter through the existing
   quorum-certified admission path.

## Safety and compatibility contract

- The flag defaults to false. Without it, proof-signer ordering and the
  pre-existing non-signer self-only fallback are unchanged.
- It adds no snapshot field, state-proof field, activation ordinal, or
  deterministic configuration input. Existing snapshot decoders and state-
  proof verification are unchanged.
- It does intentionally choose a different initial facilitator set for the
  first post-rollback outcome. Consequently the new history's facilitator set,
  `facilitatorsHash`, proof population, and later consensus history differ from
  the history that normal multi-signer rollback would have produced. This is
  the purpose of the recovery operation, not config-hash neutrality between two
  simultaneously running rollback nodes.
- Runtime flags are not part of the jar/config handshake. The software cannot
  reliably detect a second isolated recovery node: before session creation and
  peer discovery, local cluster storage can legitimately be empty. Operational
  coordination is therefore the safety boundary.
- Deploy the same jar to every metagraph node, but pass the flag to exactly one
  process and exactly one rollback invocation.

Currency L0 validator startup now sets the existing `validatorMode` marker,
matching DAG L0. `ConsensusRoundRunner` uses that marker to prevent a joining
validator whose temporary local view contains only itself from producing a
competing solo history. Rollback startup does not set validator mode, because
the designated recovery node must be able to produce the bootstrap rounds.

## Why the committee grows back

The recovery node does not remain permanently solo when returning peers are
healthy:

- A joining validator can finish `Download.observe` once the solo node is
  advancing. `startFacilitatingAfterDownload` initializes it from the observed
  outcome.
- `ConsensusEventLoop` collects registration keys from responsive peers in
  `Observing`, `WaitingForReady`, and `Ready`. `ConsensusStorage.registerPeer`
  records both the reported key and its successor, and registrations remain
  candidates on later keys until the peer leaves.
- Open Ready-at-tip admission votes use the current Core quorum. For a singleton
  Currency L0 committee with `quorum-threshold-fraction = 1.0`, that quorum is
  one. Membership still changes only after an `AdmissionCertificate` is carried
  in an accepted proposal.
- `ConsensusPeerController.applyCertifiedAdmissions` appends the certified
  peer to the canonical parent committee. Below the emergency active floor
  (default `activeFacilitatorFloor = 4`), active admission retains all available
  selected peers. `CommitteeBuilder` then promotes as many available healthy
  peers as it can toward IntegrationNet's Core floor of nine; the floor does not
  invent unavailable seats.

Admission certificates attached to one proposal are bounded by the existing
`activeAdmissionMaxExpansionPerRound` setting (default one). Normal active-set
expansion is additionally cadenced by
`activeAdmissionExpansionIntervalRounds` in the Currency state creator (default
one). The below-floor emergency path deliberately retains all available
selected peers, so cadence cannot strand a recovered one-to-three-node
metagraph below its safety floor. If the release bundle also gates open vote and
certificate emission on a wider cadence, a returning peer waits for the next
eligible cadence ordinal; penalty/probation readmission remains its separate
recovery lane. In a three-node metagraph, two accepted admission certificates
therefore restore all three peers, and the available supply causes all three to
be classified Core. Network delay and proposal timing can add rounds, so this
is a protocol bound on admission rate rather than a wall-clock SLA.

## Rollback and metagraph sync-data interaction

The flag is consumed only after `programs.rollback.rollback` returns the chosen
Currency snapshot and context. The `metagraphSyncData` fast-path lookup,
fallback snapshot walk, state reconstruction, cleanup, and storage
initialization all run exactly as before. The flag changes only the facilitator
lists used to seed `CurrencyConsensusOutcome` after rollback has completed.

## DAG L0 parity

DAG L0 rollback also derives its committee from checkpoint proof signers, but a
Global L0 singleton override has different network-wide safety and operational
requirements. This change deliberately does not add a DAG L0 flag and does not
move the small Currency-specific policy helper into `node-shared`. Sharing the
helper would expose an operation whose safety contract is not shared.

## Coordinated recovery runbook

### Preconditions

1. Confirm the metagraph is fully stopped and record its last accepted Currency
   snapshot ordinal and hash from Global L0.
2. Disable systemd/container/monitoring automatic restarts and stop every
   Currency L0 process. Verify that no other node is advancing or running
   rollback from the same checkpoint.
3. Deploy the same recovery-capable jar to all nodes.
4. Select one stable node as the sole recovery producer. Do not start rollback
   on the other nodes.
5. Pass `--allow-solo-consensus` only on the operator's one-shot command line.
   Do not edit any persistent service or monitoring configuration to add it.

### Bootstrap the producer

1. Start the selected node with its normal rollback arguments plus
   `--allow-solo-consensus`.
2. Confirm the startup warning contains `DANGER` and "Exactly one coordinated
   recovery node".
3. Confirm:
   - `dag_consensus_rollback_bootstrap_total{mode="forced_self_only"}` increments;
   - `dag_consensus_rollback_proof_signer_count` reports the checkpoint signer
     count;
   - `dag_consensus_rollback_bootstrap_facilitator_count` is `1`.
4. Wait until at least five new Currency ordinals have finalized. This proves
   both solo progress and enough chain depth for the first validator's `tip + 4`
   observation.
5. Before any service manager or monitor is re-enabled, confirm its configured
   command is the ordinary startup command and contains no
   `--allow-solo-consensus` flag.

Stop and investigate if a second node reports `forced_self_only`, if Global L0
shows competing Currency snapshot hashes, or if the producer cannot advance.

### Rejoin validators one at a time

1. Start node 2 with normal `run-validator` and join it to the producer.
2. Wait for node 2 to become `Ready`, then confirm the consensus committee and
   snapshot proofs contain both peers. Do not infer success from cluster
   membership alone.
3. Start node 3 with normal `run-validator` and repeat the checks until committee
   membership and proofs contain all three peers.
4. Confirm ordinals continue advancing at the expected cadence, then re-enable
   monitoring and automatic restarts.

The rollback flag is not used by either joining validator. A
`dag_consensus_validator_solo_blocked` increment during a transient one-peer
local view is a safety action; it should cease once the node follows the
producer's outcome and is admitted.

## Source anchors

These line references describe the branch at the time this runbook was written:

- CLI default and explicit flag:
  [`method.scala`](../../modules/currency-l0/src/main/scala/io/constellationnetwork/currency/l0/cli/method.scala#L243-L283).
- Default-vs-forced bootstrap selection, warning, and metrics:
  [`CurrencyL0App.scala`](../../modules/currency-l0/src/main/scala/io/constellationnetwork/currency/l0/CurrencyL0App.scala#L73-L79)
  and the [rollback call site](../../modules/currency-l0/src/main/scala/io/constellationnetwork/currency/l0/CurrencyL0App.scala#L432-L466).
- Currency validator-mode initialization:
  [`CurrencyL0App.scala`](../../modules/currency-l0/src/main/scala/io/constellationnetwork/currency/l0/CurrencyL0App.scala#L321-L359)
  and the shared solo-production guard in
  [`ConsensusRoundRunner.scala`](../../modules/node-shared/src/main/scala/io/constellationnetwork/node/shared/infrastructure/consensus/engine/ConsensusRoundRunner.scala#L102-L129).
- Rollback fast-path and `metagraphSyncData` resolution:
  [`Rollback.scala`](../../modules/currency-l0/src/main/scala/io/constellationnetwork/currency/l0/snapshot/programs/Rollback.scala#L75-L160).
- Four-ordinal observation and facilitation handoff:
  [`Download.scala`](../../modules/currency-l0/src/main/scala/io/constellationnetwork/currency/l0/snapshot/programs/Download.scala#L75-L173).
- Peer registration lifecycle and persistence:
  [`ConsensusEventLoop.scala`](../../modules/node-shared/src/main/scala/io/constellationnetwork/node/shared/infrastructure/consensus/engine/ConsensusEventLoop.scala#L316-L330)
  and
  [`ConsensusStorage.scala`](../../modules/node-shared/src/main/scala/io/constellationnetwork/node/shared/infrastructure/consensus/ConsensusStorage.scala#L925-L957).
- Singleton admission quorum and stall feasibility:
  [`StallDetector.scala`](../../modules/node-shared/src/main/scala/io/constellationnetwork/node/shared/infrastructure/consensus/engine/StallDetector.scala#L1330-L1365)
  and the [feasibility check](../../modules/node-shared/src/main/scala/io/constellationnetwork/node/shared/infrastructure/consensus/engine/StallDetector.scala#L1596-L1619).
- Certified committee append:
  [`ConsensusPeerController.scala`](../../modules/node-shared/src/main/scala/io/constellationnetwork/node/shared/infrastructure/consensus/ConsensusPeerController.scala#L185-L193)
  and
  [`CurrencySnapshotConsensusStateAdvancer.scala`](../../modules/currency-l0/src/main/scala/io/constellationnetwork/currency/l0/snapshot/CurrencySnapshotConsensusStateAdvancer.scala#L398-L403).
- Emergency floor, expansion budget, and cadence defaults:
  [`types.scala`](../../modules/node-shared/src/main/scala/io/constellationnetwork/node/shared/config/types.scala#L327-L365);
  cadence application:
  [`CurrencySnapshotConsensusStateCreator.scala`](../../modules/currency-l0/src/main/scala/io/constellationnetwork/currency/l0/snapshot/CurrencySnapshotConsensusStateCreator.scala#L267-L274).
- IntegrationNet Core floor:
  [`currency-l0.conf`](../../modules/currency-l0/src/main/resources/currency-l0.conf#L25-L34);
  deterministic tier construction:
  [`CommitteeBuilder.scala`](../../modules/node-shared/src/main/scala/io/constellationnetwork/node/shared/infrastructure/consensus/CommitteeBuilder.scala#L147-L230).
