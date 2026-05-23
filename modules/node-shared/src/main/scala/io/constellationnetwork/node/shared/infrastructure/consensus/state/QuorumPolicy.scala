package io.constellationnetwork.node.shared.infrastructure.consensus.state

/** Integer quorum policy helper. Single source of truth for the supermajority threshold across the Facility, Proposal, Signature,
  * B1/B2/VCC, and stall-feasibility code paths.
  *
  * ==Why this exists==
  *
  * Prior to this helper, every consensus call site computed quorum as:
  *
  * {{{
  * val q = math.max(1, math.ceil(n.toDouble * config.quorumThresholdFraction).toInt)
  * }}}
  *
  * with `quorumThresholdFraction = 0.6666666666666666` (the max-precision Double approximation of 2/3). The pattern was duplicated across
  * StallDetector, StateTransitions (VCC/ECS/ACS assembly), ProposalVccValidator, ConsensusStateAdvancer (maybeGetAllDeclarations), and both
  * env-specific advancers in dag-l0 and currency-l0. Each duplication is a risk of drift; using Double for BFT-protocol arithmetic is
  * unnecessary error surface.
  *
  * HotStuff-family protocols specify integer quorum thresholds. The exact 2/3 supermajority is `(2 * n + 2) / 3`, which gives `ceil(2n/3)`
  * for every n via integer division. We verify in `QuorumPolicySuite` that this matches the legacy `ceil(n * 0.6666...)` formula for every
  * cluster size we operate at.
  *
  * `fromFraction` is the call-site bridge that maps the configured fractional knob (`quorum-threshold-fraction = 1.0` or
  * `0.6666666666666666`) to a named integer policy. It does NOT perform Double math past the dispatch check -- unrecognized fractions fail
  * fast so a typo like `0.5` cannot silently bypass the named-mode discipline (and the schema-hash gate).
  *
  * ==Determinism==
  *
  * All methods are pure integer arithmetic. No floating-point. No locale-dependent formatting. The result for any given `n` is identical
  * across all JVMs / architectures.
  */
object QuorumPolicy {

  /** Exact 2/3 supermajority quorum: minimum number of signers required to advance a phase or assemble a certificate.
    *
    *   - n=0 -> 0 (caller decides whether to floor at 1; see `ConsensusStateAdvancer` for the traditional `math.max(1, ...)` wrap)
    *   - n=1 -> 1
    *   - n=3 -> 2 (2-of-3, the classic BFT minimum)
    *   - n=5 -> 4
    *   - n=7 -> 5
    *   - n=15 -> 10
    *
    * Formula: `(2 * n + 2) / 3`. Integer division produces `ceil(2n/3)` exactly.
    */
  def supermajority(n: Int): Int =
    if (n <= 0) 0 else (2 * n + 2) / 3

  /** Unanimity quorum: all `n` peers required. Used by the dev environment (single-node test rigs) where the configured fraction is 1.0.
    */
  def unanimity(n: Int): Int = math.max(0, n)

  /** Tolerance for matching `fraction` against a known named mode. The two configured fractions in the codebase are exactly `1.0`
    * (currency-l0 / dev unanimity) and `0.6666666666666666` (dag-l0 testnet/mainnet supermajority -- the max-precision Double approximation
    * of 2/3). Any fraction outside this epsilon band is rejected at the call site, see `fromFraction`.
    */
  private val Epsilon: Double = 1.0e-9

  /** Config-time dispatch from a fractional knob to a named integer policy. The codebase has two legitimate modes:
    *
    *   - `fraction == 1.0` -> [[unanimity]] (dev / currency-l0 default)
    *   - `fraction == 2/3` (max-precision Double `0.6666666666666666`) -> [[supermajority]]
    *
    * Any other fraction is rejected with `IllegalArgumentException` at the call site. This is deliberate: a fraction like `0.5` or `0.7`
    * would silently bypass the named-mode discipline (and the schema-hash gate that goes with it), so an operator who needs a new threshold
    * must add a named [[QuorumPolicy]] mode and route it explicitly. The throw is a config-load-time failure, not a runtime concern, and
    * matches the style of refined-type validation elsewhere in the codebase.
    *
    * Determinism: no Double arithmetic past the dispatch check. The integer formulas in `supermajority` and `unanimity` are byte-identical
    * across all JVMs / architectures.
    */
  def fromFraction(n: Int, fraction: Double): Int =
    if (n <= 0) 0
    else if (math.abs(fraction - 1.0) < Epsilon) unanimity(n)
    else if (math.abs(fraction - 2.0 / 3.0) < Epsilon) supermajority(n)
    else
      throw new IllegalArgumentException(
        s"Unsupported quorumThresholdFraction=$fraction. Add a named QuorumPolicy mode and route it explicitly through the hash gate."
      )
}
