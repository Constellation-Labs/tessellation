package io.constellationnetwork.dag.l0.domain.snapshot.recovery

import scala.collection.immutable.SortedSet
import scala.util.control.NoStackTrace

import io.constellationnetwork.node.shared.infrastructure.consensus.CommitteeViability
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

/** Canonical, invocation-local committee input for a coordinated GL0 rollback recovery.
  *
  * This value is deliberately not a consensus/configuration schema. It is parsed from [[Gl0RecoverySeedCommittee.EnvironmentVariable]] only
  * on the controlled recovery nodes, then used to seed and verify the existing `GlobalConsensusOutcome` type. A fresh external JVM parses
  * the environment again; the running invocation disarms after its first accepted successor. Unnamed validators learn the outcome through
  * the normal authenticated download path.
  */
final case class Gl0RecoverySeedCommittee private (committee: SortedSet[PeerId])

object Gl0RecoverySeedCommittee {
  val EnvironmentVariable: String = "CL_GL0_RECOVERY_SEED_COMMITTEE"

  /** The trusted recovery path is deliberately stricter than generic committee viability.
    *
    * Two nodes can technically certify a third seat at a 2/3 threshold, but a two-member env also lets two accidentally identically
    * truncated source configurations release without the intended third source. The production recovery topology has three controlled
    * sources, so omission of any one must fail at startup rather than silently reduce recovery authority.
    */
  val MinimumRecoveryCommitteeSize: Int = 3

  private val CanonicalPeerId = "[0-9a-f]{128}".r

  sealed trait Error extends NoStackTrace {
    def reason: String
    override final def getMessage: String = s"invalid $EnvironmentVariable: $reason"
  }

  final case class Invalid(reason: String) extends Error

  /** Parse a comma-separated PeerId list into canonical order.
    *
    * Whitespace around entries is accepted for operator ergonomics. Empty entries, duplicates, uppercase, non-hex, and non-128-character
    * values fail closed before any rollback storage is touched.
    */
  def parse(raw: String): Either[Error, Gl0RecoverySeedCommittee] = {
    val entries = raw.split(",", -1).toList.map(_.trim)
    val emptyPositions = entries.zipWithIndex.collect { case (value, index) if value.isEmpty => index + 1 }
    val malformed = entries.filterNot(value => CanonicalPeerId.matches(value))
    val duplicates =
      entries.groupBy(identity).collect { case (value, occurrences) if occurrences.sizeCompare(1) > 0 => value }.toList.sorted

    Either
      .cond(raw.trim.nonEmpty, (), Invalid("value is empty"): Error)
      .flatMap(_ =>
        Either.cond(
          emptyPositions.isEmpty,
          (),
          Invalid(s"empty committee entry at position(s)=${emptyPositions.mkString(",")}"): Error
        )
      )
      .flatMap(_ =>
        Either.cond(
          malformed.isEmpty,
          (),
          Invalid(s"PeerIds must be exactly 128 lowercase hexadecimal characters; invalid=${malformed.mkString(",")}"): Error
        )
      )
      .flatMap(_ =>
        Either.cond(
          duplicates.isEmpty,
          (),
          Invalid(s"duplicate PeerIds=${duplicates.mkString(",")}"): Error
        )
      )
      .map(_ => Gl0RecoverySeedCommittee(SortedSet.from(entries.map(value => PeerId(Hex(value))))))
  }

  /** Apply the trusted unsigned seed's membership and quorum boundary.
    *
    * `requiredMember` is the rollback lead in rollback mode and the local node in validator mode. A configured seed therefore cannot be
    * consumed accidentally by an unrelated node. Collateral is checked separately against the exact loaded/downloaded anchor context.
    */
  def validate(
    seed: Gl0RecoverySeedCommittee,
    requiredMember: PeerId,
    seedlist: Set[PeerId],
    allowanceList: Option[Set[PeerId]],
    maxFacilitatorCount: Option[Int],
    quorumThresholdFraction: Double
  ): Either[Error, Gl0RecoverySeedCommittee] = {
    val committee = seed.committee
    val outsideSeedlist = committee.diff(seedlist)
    val outsideAllowance = allowanceList.fold(SortedSet.empty[PeerId])(committee.diff)

    Either
      .cond(
        committee.size >= MinimumRecoveryCommitteeSize,
        (),
        Invalid(
          s"size=${committee.size}, minimum=$MinimumRecoveryCommitteeSize"
        ): Error
      )
      .flatMap(_ =>
        Either.cond(
          CommitteeViability.canProveNextSeat(committee.size, quorumThresholdFraction),
          (),
          Invalid(
            s"size=${committee.size} cannot certify the next seat under quorum-threshold-fraction=$quorumThresholdFraction"
          ): Error
        )
      )
      .flatMap(_ =>
        Either.cond(
          committee.contains(requiredMember),
          (),
          Invalid(s"required recovery member=${requiredMember.value.value} is not in committee"): Error
        )
      )
      .flatMap(_ =>
        Either.cond(
          maxFacilitatorCount.forall(committee.size <= _),
          (),
          Invalid(s"size=${committee.size} exceeds max-facilitator-count=${maxFacilitatorCount.getOrElse(0)}"): Error
        )
      )
      .flatMap(_ =>
        Either.cond(
          outsideSeedlist.isEmpty,
          (),
          Invalid(s"not in seedlist=${outsideSeedlist.toList.map(_.value.value).mkString(",")}"): Error
        )
      )
      .flatMap(_ =>
        Either.cond(
          outsideAllowance.isEmpty,
          (),
          Invalid(s"not in allowance list=${outsideAllowance.toList.map(_.value.value).mkString(",")}"): Error
        )
      )
      .map(_ => seed)
  }
}
