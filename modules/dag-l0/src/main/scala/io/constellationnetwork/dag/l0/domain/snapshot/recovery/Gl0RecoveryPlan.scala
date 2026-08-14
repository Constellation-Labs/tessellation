package io.constellationnetwork.dag.l0.domain.snapshot.recovery

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedSet
import scala.util.control.NoStackTrace

import io.constellationnetwork.node.shared.infrastructure.consensus.CommitteeViability
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.{Signed, SignedValidator}
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import derevo.cats.eqv
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

/** An operator-authorized, one-shot Global L0 rollback plan.
  *
  * The plan is deliberately outside snapshot, state-proof, consensus-message, and deterministic-configuration schemas. Exactly one
  * designated lead consumes it in rollback mode; every other planned member verifies and consumes the same file in validator mode. The
  * all-member first-round gate then aligns those nodes on the resulting initial outcome. The lead's signature provides tamper evidence and
  * binds the plan to the key that is authorized to execute it. It is not protocol finality and does not make an unsafe anchor safe.
  *
  * `committee` is a `SortedSet` so the initial facilitator order and the signed plan bytes cannot depend on JSON/input ordering.
  */
@derive(eqv, encoder, decoder)
final case class Gl0RecoveryPlan(
  protocol: String,
  formatVersion: Int,
  planId: Hash,
  anchor: RecoveryCheckpoint,
  lead: PeerId,
  committee: SortedSet[PeerId]
)

object Gl0RecoveryPlan {
  val CurrentProtocol: String = "gl0-recovery-plan-v1"
  val CurrentFormatVersion: Int = 1
  val MinimumCommitteeSize: Int = CommitteeViability.MinimumCoordinatedCommitteeSize
  private val CanonicalPlanId = "[0-9a-f]{64}".r

  def isCanonicalPlanId(planId: Hash): Boolean = CanonicalPlanId.matches(planId.value)

  sealed trait PlanError extends NoStackTrace {
    def message: String
    override final def getMessage: String = message
  }

  final case class UnsupportedFormatVersion(expected: Int, got: Int) extends PlanError {
    val message: String = s"unsupported GL0 recovery-plan format: expected=$expected got=$got"
  }

  final case class UnsupportedProtocol(expected: String, got: String) extends PlanError {
    val message: String = s"unsupported GL0 recovery-plan protocol: expected='$expected' got='$got'"
  }

  final case class NetworkMismatch(expected: String, got: String) extends PlanError {
    val message: String = s"GL0 recovery-plan network mismatch: expected='$expected' got='$got'"
  }

  final case class LeadMismatch(expected: PeerId, got: PeerId) extends PlanError {
    val message: String = s"GL0 recovery-plan lead mismatch: expected=${expected.value.value} got=${got.value.value}"
  }

  final case class InvalidSignatures(reason: String) extends PlanError {
    val message: String = s"GL0 recovery-plan signature validation failed: $reason"
  }

  final case class InvalidCommittee(reason: String) extends PlanError {
    val message: String = s"invalid GL0 recovery-plan committee: $reason"
  }

  final case class RollbackHashMismatch(expected: Hash, got: Hash) extends PlanError {
    val message: String = s"GL0 recovery-plan rollback hash mismatch: expected=${expected.value} got=${got.value}"
  }

  final case class AnchorOrdinalMismatch(expected: Long, got: Long) extends PlanError {
    val message: String = s"GL0 recovery-plan anchor ordinal mismatch: expected=$expected got=$got"
  }

  final case class AnchorHashMismatch(expected: Hash, got: Hash) extends PlanError {
    val message: String = s"GL0 recovery-plan loaded anchor hash mismatch: expected=${expected.value} got=${got.value}"
  }

  final case class UnsupportedAnchorSource(got: String) extends PlanError {
    val message: String =
      s"GL0 recovery-plan v1 requires an incremental snapshot rollback hash; loaded anchor source=$got"
  }

  final case class IneligibleCommitteeMembers(reason: String) extends PlanError {
    val message: String = s"GL0 recovery-plan contains ineligible committee members: $reason"
  }

  def validateLoadedAnchor(plan: Gl0RecoveryPlan, ordinal: Long, hash: Hash): Either[PlanError, Unit] =
    Either
      .cond(
        plan.anchor.ordinal.value.value == ordinal,
        (),
        AnchorOrdinalMismatch(plan.anchor.ordinal.value.value, ordinal): PlanError
      )
      .flatMap(_ => Either.cond(plan.anchor.snapshotHash === hash, (), AnchorHashMismatch(plan.anchor.snapshotHash, hash)))

  /** Validate the static authority and membership boundary before rollback mutates local storage.
    *
    * A configured recovery plan requires a seedlist. A custom allowance list remains optional, matching the normal cluster setup; when
    * present, the planned committee must be a subset of both lists. The signature must be exclusively from the designated lead.
    */
  def verify[F[_]: Async: SecurityProvider](
    signedValidator: SignedValidator[F],
    expectedNetwork: String,
    expectedLead: PeerId,
    rollbackHash: Hash,
    seedlist: Set[PeerId],
    allowanceList: Option[Set[PeerId]],
    maxFacilitatorCount: Option[Int],
    quorumThresholdFraction: Double,
    signed: Signed[Gl0RecoveryPlan]
  )(implicit hasher: Hasher[F]): F[Either[PlanError, Gl0RecoveryPlan]] = {
    val plan = signed.value
    val proofSigners = SortedSet.from(signed.proofs.toSortedSet.toList.map(_.id.toPeerId))
    val committeeOutsideSeedlist = plan.committee.diff(seedlist)
    val committeeOutsideAllowance = allowanceList.fold(SortedSet.empty[PeerId])(plan.committee.diff)

    val staticValidation: Either[PlanError, Unit] =
      Either
        .cond(
          plan.protocol === CurrentProtocol,
          (),
          UnsupportedProtocol(CurrentProtocol, plan.protocol): PlanError
        )
        .flatMap(_ =>
          Either.cond(
            plan.formatVersion == CurrentFormatVersion,
            (),
            UnsupportedFormatVersion(CurrentFormatVersion, plan.formatVersion): PlanError
          )
        )
        .flatMap(_ => Either.cond(plan.anchor.network === expectedNetwork, (), NetworkMismatch(expectedNetwork, plan.anchor.network)))
        .flatMap(_ =>
          Either.cond(isCanonicalPlanId(plan.planId), (), InvalidCommittee("planId must be 64 lowercase hexadecimal characters"))
        )
        .flatMap(_ => Either.cond(plan.lead === expectedLead, (), LeadMismatch(expectedLead, plan.lead)))
        .flatMap(_ =>
          Either.cond(plan.anchor.snapshotHash === rollbackHash, (), RollbackHashMismatch(plan.anchor.snapshotHash, rollbackHash))
        )
        .flatMap(_ =>
          Either.cond(
            plan.committee.size >= MinimumCommitteeSize,
            (),
            InvalidCommittee(s"size=${plan.committee.size}, minimum=$MinimumCommitteeSize")
          )
        )
        .flatMap(_ =>
          Either.cond(
            CommitteeViability.canProveNextSeat(plan.committee.size, quorumThresholdFraction),
            (),
            InvalidCommittee(
              s"size=${plan.committee.size} cannot certify the next seat under quorum-threshold-fraction=$quorumThresholdFraction"
            )
          )
        )
        .flatMap(_ => Either.cond(plan.committee.contains(plan.lead), (), InvalidCommittee("designated lead is not in committee")))
        .flatMap(_ =>
          Either.cond(
            maxFacilitatorCount.forall(plan.committee.size <= _),
            (),
            InvalidCommittee(s"size=${plan.committee.size} exceeds max-facilitator-count=${maxFacilitatorCount.getOrElse(0)}")
          )
        )
        .flatMap(_ =>
          Either.cond(
            committeeOutsideSeedlist.isEmpty,
            (),
            InvalidCommittee(s"not in seedlist=${committeeOutsideSeedlist.toList.map(_.value.value).mkString(",")}")
          )
        )
        .flatMap(_ =>
          Either.cond(
            committeeOutsideAllowance.isEmpty,
            (),
            InvalidCommittee(s"not in allowance list=${committeeOutsideAllowance.toList.map(_.value.value).mkString(",")}")
          )
        )
        .flatMap(_ =>
          Either.cond(
            proofSigners === SortedSet(plan.lead),
            (),
            InvalidSignatures(
              s"plan must be signed exclusively by lead=${plan.lead.value.value}; got=${proofSigners.toList.map(_.value.value).mkString(",")}"
            )
          )
        )

    staticValidation match {
      case Left(error) => error.asLeft[Gl0RecoveryPlan].pure[F]
      case Right(_) =>
        signedValidator
          .validateSignatures(signed)
          .map(
            _.productL(signedValidator.validateUniqueSigners(signed)).toEither
              .leftMap(errors => InvalidSignatures(errors.toNonEmptyList.toList.mkString(", ")): PlanError)
              .map(_ => plan)
          )
    }
  }
}
