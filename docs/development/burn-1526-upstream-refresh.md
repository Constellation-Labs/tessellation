# PR #1526 — upstream refresh review notes

Review notes for the upstream refresh. Publication and merge remain subject to maintainer review.

## Inputs and scope

- Preserved published revision: `42fbf067d6e4aeee50a223809f51b656dc294f81`.
- Refreshed upstream base: `827bfec18cde5580a601f2ddca6b38b2a422159b` (`develop`).
- Previous base: `11cce41dbb6c6d8be17e48a29718f899f68ff3d2`.
- Self-burn only; source and currency must equal the emitting metagraph. No delegated,
  AllowSpend-authorized, DAG or foreign-holder burns. No activation is added.

Compatibility classification remains **schema/wire** as recorded in
[the existing scope and rollout packet](metagraph-native-self-burn.md). This refresh adds no
new signed field, codec, hash rule or activation boundary. It does not grant the outstanding
cross-consumer rollout or upstream schema approval.

## Conflict resolution

Retain both the upstream aggregate-spend gate and the optional burn gate in the configuration
case class, selectors, effective hash input, packaged-config tests and operator table. Retain
the upstream named cursor reader: historical keys remain required; only burn activation is
optional and disabled when absent. Malformed optional configuration still rejects. Retain both
upstream aggregate-spend fixture controls and the feature's real state-channel/validator
injection points.

Upstream #1618 running-balance validation and #1594 individually validated fees remain intact.
An automatic merge of their Currency callsites is not treated as compatibility evidence.

## Additional correction exposed by the combined regression

After a settled spend and accepted fee, a legitimate burn was produced but failed independent
same-ordinal recreation with different artifacts/state proof. The native burn guard rejected it
as `PendingGlobalChanges`.

Legacy dependency processing reconsumes `alreadyForCurrent ++ newlyRequired`, but deliberately
returns only `newlyRequired` as its emitted acknowledgment. Equating that acknowledgment with
consumption makes an empty newly-required set look like an unsettled dependency on recreation.

The correction separates successful **invocation-local consumption evidence** from historical
**emitted declarations**. The burn guard uses the former. The existing pair-returning service
API and historical declaration values remain unchanged; a package-private method carries the
additional evidence. No caller-supplied acknowledgment, later live cache lookup or inferred
sync ordinal is accepted as proof of consumption. Missing/future dependencies still prevent
burning, and dependency-resolution errors still fail. The correction does not weaken spend,
fee, balance, signature or state-proof validation.

## Regression coverage

- Spend 60 plus valid fee 10 and invalid self-directed fee 90: preserve the spend, apply only
  the valid fee, accept burn 30, reject burn 31, and compare exact reconstructed Currency state
  and proof. Verify both repeated same-service and independent fresh-service reconstruction.
- New distinct spend actions 40+40 after fee 10: both fit balance 90; after burn 20 leaves 70,
  only one fits the upstream running balance.
- Separate legacy consumption/declaration tests: same-ordinal reconsumption, unchanged public
  API declarations, future pending ordinal, no consumption inherited from another Currency
  ordinal, and failure without cache publication.
- Config reader: distinct mappings, optional/default-off burn, malformed burn rejection, and
  required historical keys.
- Existing disabled/preactivation hash/replay compatibility, activation boundaries, malicious
  signed claims, opaque channels, pending-spend guard, exact arithmetic, and later legitimate
  equal burns remain covered.

Recorded refresh qualification: 360 focused checks passed; the full import/format/test gate
passed 2,709 tests with two unchanged upstream ignores. An independent selected-suite run
passed 324 tests. These selections overlap the full suite and are not additive. See the
[validation commands and historical results](metagraph-native-self-burn.md#validation).

All tests are in-process integration/unit checks, not live network qualification. Publication
sanitization changes documentation and commit metadata only; the executable sources and test
assertions are identical to those qualified. No additional test run is claimed for that copy.
Upstream activation/consumer rollout remain separate gates; Euclid PR #88 stays deferred.
