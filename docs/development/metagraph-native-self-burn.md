# Native metagraph self-burn

Status: proposal for PR #1526; maintainer review and rollout approval remain required.
Burning is disabled in every packaged environment. No live-network acceptance is claimed.
Base: upstream `develop` `11cce41dbb6c6d8be17e48a29718f899f68ff3d2`.
Replaces the implementation proposed at PR head `de08653be62d5cde164dc1812da20ee0c7ab8945`;
the Euclid demo is unchanged and requires separate review before use with this revision.

## Supported operation and accounting

`BurnAction(NonEmptyList[BurnTransaction])` destroys the emitting metagraph's **own native
currency**, from that metagraph's **own address**. Each transaction carries required
`currencyId`, positive `amount` and `source`. Both addresses must equal the emitter.
DAG (`currencyId: null`), missing currency, delegated `allowSpendRef`, `intentHash`,
destination and unknown fields reject strict decoding. A validly encoded different currency
or holder source rejects native validation. No allowance grants burn authority.

The Currency acceptance manager first performs its existing transaction, reward, fee,
lock, allowance, accepted-Global-spend and authorized-adjustment processing. A single checked
fold validates and applies burns against that resulting balance. Actions use canonical
`SortedSet` order; transactions inside each action retain list order. An action either
debits completely or leaves no effect. Later actions see earlier successful debits. Arithmetic
underflow returns a typed rejection, never a throw, wrapping sum or clamped residual.

A lagging Currency view must not consume backing funds already committed to an unapplied
Global change. Burns therefore reject while the metagraph's retained unapplied ordinals
contain dependencies not acknowledged by this acceptance or the signed parent. Ordinary
snapshots and spending continue; the application can request a burn again after catch-up.
An emitted SpendAction is not itself a finalized spend: only Global-accepted spends enter
Currency settlement. This proposal does not change spend authority or its acceptance order.

Accepted burns reduce liquid balance and aggregate native holdings by the same amount;
there is no second mutable supply counter. Locked funds and allowances are not burned.
Global-L0 reconstructs Currency acceptance from its previous Currency state and compares
the resulting snapshot and state proof. It does **not** debit Currency balances a second
time, and never burns DAG. The no-application recreation path must retain reconstructed
native burn artifacts, not overwrite them with the metagraph's claim. The initial
full-genesis-to-first-incremental projection cannot carry active burns: that step executes no
operations and has no ordinary incremental parent to reconstruct.
After activation, malformed native burn claims also reject instead of falling through to the legacy opaque
state-channel path. Unrelated opaque payload behavior is unchanged. Newly requested spends
are validated by the existing Global pipeline against the reconstructed post-burn balance;
they cannot spend balance that this Currency snapshot has already destroyed.

Exactly-once means a finalized state-channel/Currency snapshot is not applied twice.
It does not prohibit a separately finalized later burn with the same amount, nor repeated
equal amounts within one action. No opaque application intent is treated as a replay key.

## Activation, replay and operational failure handling

`fields-added-ordinals.burn-action-activation` is optional. Missing map/environment resolves
to `SnapshotOrdinal.MaxValue`, explicitly disabled even at that sentinel. No environment is
enabled by the packaged configuration. A malformed or negative configured ordinal fails
configuration loading; an absent entry does not fail snapshot processing.

For a finite threshold `A`, eligibility is `signedParent.globalSyncView.ordinal >= A`;
an absent parent view contributes ordinal zero. This is a **Global** ordinal, not the new
Currency ordinal, wall clock or live head. The same acceptance routine runs at the producer,
Global validator and historical reconstruction. The resolved threshold participates in
both L0 deterministic configuration hashes. Existing historical thresholds are untouched.

This gate controls **recognition and interpretation**, not only deduction. Before activation
(including absent configuration and the disabled sentinel), the Global Currency decoder
preserves upstream's result: a payload containing the newly recognized `BurnAction` variant
is not a Currency incremental snapshot. A malformed burn marker likewise has no new native
meaning. Both follow the existing opaque-channel behavior: with no required fee, accept the
signed binary and advance its state-channel hash without replacing Currency state; when fees
require parseable Currency state, reject as before. Accepting an opaque binary is **not**
accepting or executing its claimed burn. Its bytes and signatures are never rewritten.

The same recognition rule covers fee-message extraction, so formerly opaque owner/staking
metadata cannot change admission. The accepted full or incremental Currency parent selects
the gate, never an incoming claimed view or the processing Global ordinal. Selected branches
reevaluate it after each accepted parent. If a branch crosses activation within one Global
batch, an active burn must also pass the existing active state-channel fee/address validation;
the initial opaque admission is insufficient. Recheck **only that child**, using its actual
accepted parent, against the initial admission's fee-address context. Do not force other
channels or earlier opaque payloads to become burn-enabled. Where a multi-snapshot branch
can cross activation, reconstruct channels in deterministic address order and extend this
context only with fee addresses from accepted Currency states. A rejected branch or opaque
claim creates no new fee reservation; two newly active channels cannot acquire the same
address. Already admitted fee reservations remain protected. Disabled activation and batches
without a possible crossing retain parallel reconstruction; initial admission and ordinary
non-burn interpretation remain unchanged.

Disabled, unauthorized, pending-dependency or unaffordable proposed burns are omitted
without changing their balances; independent valid actions remain possible. A malicious
signed snapshot that insists such a burn executed is not accepted as the next Currency
state. Before activation its opaque binary may still advance the state-channel head as
described above. Applications must inspect finalized artifacts/state before reporting a burn complete.
Retry after rejection is a fresh application decision, not proof that an earlier request
executed. Restarts recover the normal signed Currency/Global state; no burn-specific
process-local replay cache or historical-data migration is introduced.

Self-burn limits consensus authority, **not custody risk**: the metagraph address may hold
customer escrow. Who may cause the application to emit a treasury burn remains an application
authorization responsibility. No claim is made that an arbitrary malicious metagraph, all
spending paths, or its custody arrangements are made safe by this patch.

## Consumer impact and scope

`intentHash` is removed: this proposal defines no authorization, replay or receipt semantics
for that field. It supplies a native self-burn primitive, not a bridge withdrawal protocol.
Holder-authorized burning and DAG burning are outside this PR.

| Consumer | Required work before activation |
|---|---|
| Tessellation SDK and metagraph applications | Rebuild against the new artifact schema and explicitly adopt the self-burn-only constructor. |
| Snapshot Streaming | Qualify decoding, Currency reconstruction, state proofs and indexing using the agreed SDK and activation configuration. |
| Block Explorer and APIs | Qualify artifact rendering and native balance/supply reconciliation. |
| Euclid demo PR #88 | Adapt its old constructor and review application authorization, finalized-result reporting and operational behavior separately. The demo is not changed here. |

These are compatibility requirements, not completed external-consumer qualifications.
No deployed consumer artifacts were tested for this proposal. The demo's repository placement
and application authorization remain separate maintainer decisions.

## Consensus compatibility: schema/wire change (ADR-0034 class 3)

Adding a new signed `SharedArtifact` variant changes an SDK-visible consensus schema. It is
not a runtime-only fix. The proposed scope is native self-burn only; upstream schema approval
and network rollout authorization are **not** implied by this candidate.

1. **Approval:** this package requests maintainer approval of the new native burn artifact.
   An ordinary transfer does not express native supply destruction. The schema and rollout
   decision remains outstanding; this document does not assert approval.
2. **Changed surfaces:** `BurnAction`/`BurnTransaction`, strict JSON codecs, canonical artifact
   ordering, signed Currency artifact bytes and their existing hash/state-proof derivation;
   the new configuration threshold. No `GlobalSnapshot` genesis field, Kryo registration,
   new hash algorithm, DAG supply mutation or persistent burn ledger.
3. **Compatibility:** before activation, new nodes preserve upstream interpretation, hashes
   and state proofs even for opaque payloads containing malformed or newly decodable valid
   burn carriers. Mixed-channel tests also verify that an inactive channel's opaque metadata
   cannot newly reserve a fee address or reject another channel's active burn. These are
   regression scenarios, not claims that the payloads occur in public history. Old SDKs
   cannot decode new burn artifacts; all producing/consuming components must upgrade before
   activation. Once active, the earlier unmerged PR's optional DAG/delegated/intent fields are rejected,
   not silently reinterpreted. No public burn history is asserted to exist.
4. **Transition:** disabled-by-default finite Global-ordinal gate, identical producer and
   reconstruction law. Do not backdate activation, move crossed gates, or downgrade to
   binaries unable to read burn history after activation. Rollback replays from a checkpoint
   with the same schema-capable binary and the original activation configuration.
5. **Snapshot Streaming:** must consume the exact reviewed SDK/config and qualify Currency reconstruction/state
   proofs with burn fixtures before deployment. No Streaming artifact digest or production
   indexing/reindex acceptance is claimed here. Existing pre-activation history is unchanged;
   consumer owners must decide the new artifact projection and any index migration.
6. **SDK/templates/metagraphs:** rebuild Currency L0/L1, Data L1 and affected consumers from
   the reviewed schema-capable SDK; publish distinctly versioned artifacts. No official
   coordinates are overwritten or experimental SDK published by this correction.
7. **Explorer/API:** consumers must decode/render finalized native self-burns and reconcile
   balance/supply deltas. Requested burns are not finalized burns. Consumer qualification
   and exact deployed digests remain prerequisites to public activation.
8. **Evidence:** strict JSON/Brotli/hash fixtures, arithmetic and mutation negatives,
   activation A-1/A/A+1, real signed Currency-to-Global acceptance, pending-spend collision,
   parent/genesis restrictions, restored-state replay and later equal-amount burns. Coverage
   includes disabled/future replay cases, valid-carrier and A-1/A/A+1 matrices in Full
   and Historical validation, both Global proof formats, opaque fee metadata, required-fee
   rejection, and same-batch activation/fee-admission regressions. Additional tests cover mixed-channel
   Owner/Staking isolation, input-order permutations, competing valid crossing claims,
   and rejected-claim non-reservation in both validation modes. The
   validation section below records the in-repository commands and results; no live fleet proof is claimed.
9. **Release:** a coordinated, distinctly versioned full-cluster restart and agreed future
   activation require maintainer/operator approval and all consumer qualifications above.
   Record binaries/config hashes, checkpoints and native balance/supply reconciliation;
   keep the gate disabled until that package is approved. No deployment is performed here.

These rollout entries deliberately distinguish an implementation review package from a
merge-ready or network-activation-approved release. Missing external approvals/artifacts are
not fabricated as test results.

## Validation

Toolchain: OpenJDK 21.0.12.1+1, Scala 2.13.18 and sbt 1.9.8.

- Focused activation, acceptance, validator, configuration and codec suites: 259 passed.
- Full import/format/test gate: 2,593 passed, zero test failures; two existing ignored
  data-generation tests. The focused tests overlap the full suite.
- Additional independent reviewer probes: 10 compatibility/admission cases and four
  accepted-prefix reservation cases passed. These supplemental probes are not part of the
  in-repository commands below; they do not count toward the full-suite total.

The results apply to the reviewed implementation; documentation and two test comments were
subsequently prepared for publication without changing executable sources or test assertions.
One independent full-gate invocation failed during Scala configuration-reader compilation
at `CurrencyL0App.scala:136`, with `scala.reflect.internal.FatalError` and an
`IterableFactory.apply` argument-count error. A fresh-process rerun with identical source
and command passed. The cause remains unestablished; the rerun is not a compiler fix.

Run each command sequentially, not concurrently in the same build directory:

```bash
CI=1 sbt -Dsbt.log.noformat=true \
  'nodeShared/testOnly *BurnSnapshotAcceptanceSuite *BurnActionValidatorSuite *FieldsAddedOrdinalsSuite *ConsensusOrdinalConfigSuite' \
  'shared/testOnly *BurnActionCodecSuite'

CI=1 sbt -Dsbt.log.noformat=true \
  'scalafixAll --check --rules OrganizeImports;scalafmtCheckAll;test'
```

`CI=1` disables automatic on-compile source rewriting; the explicit checks still run.
No live fleet, public-network activation, or external-consumer deployment was tested.
