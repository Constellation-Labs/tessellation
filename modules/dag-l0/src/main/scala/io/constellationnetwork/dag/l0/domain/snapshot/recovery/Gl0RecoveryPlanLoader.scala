package io.constellationnetwork.dag.l0.domain.snapshot.recovery

import cats.effect.Async
import cats.syntax.all._

import scala.util.control.NoStackTrace

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.signature.{Signed, SignedValidator}
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import fs2.io.file.{Files, Path}
import fs2.text
import io.circe.parser.decode
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Gl0RecoveryPlanLoader {

  final case class Verified(signed: Signed[Gl0RecoveryPlan]) {
    def plan: Gl0RecoveryPlan = signed.value
  }

  sealed trait Role {
    def nodeId: PeerId
    def rollbackHash(signed: Signed[Gl0RecoveryPlan]): io.constellationnetwork.security.hash.Hash
  }
  object Role {
    final case class RollbackLead(nodeId: PeerId, requestedRollbackHash: io.constellationnetwork.security.hash.Hash) extends Role {
      def rollbackHash(signed: Signed[Gl0RecoveryPlan]): io.constellationnetwork.security.hash.Hash = requestedRollbackHash
    }
    final case class PlannedValidator(nodeId: PeerId) extends Role {
      def rollbackHash(signed: Signed[Gl0RecoveryPlan]): io.constellationnetwork.security.hash.Hash = signed.value.anchor.snapshotHash
    }
  }

  case object RecoveryPlanConfiguredWithoutSeedlist extends NoStackTrace {
    override def getMessage: String = "GL0 recovery plan is configured but no seedlist is present"
  }

  final case class RecoveryPlanFileUnreadable(path: String, reason: String) extends NoStackTrace {
    override def getMessage: String = s"GL0 recovery-plan file '$path' could not be read or parsed: $reason"
  }

  /** Load and fail-closed verify an optional lead-signed plan. `None` is the entire default path and leaves normal startup unchanged.
    *
    * The lead and every named validator verify the same signature and membership boundaries. The role only determines whether this node
    * must equal the designated rollback lead or merely be a named committee member.
    */
  def load[F[_]: Async: SecurityProvider: Files](
    planPath: Option[Path],
    expectedNetwork: String,
    role: Role,
    seedlist: Option[Set[PeerId]],
    allowanceList: Option[Set[PeerId]],
    maxFacilitatorCount: Option[Int],
    quorumThresholdFraction: Double,
    signedValidator: SignedValidator[F]
  )(implicit hasher: Hasher[F]): F[Option[Verified]] = {
    val logger = Slf4jLogger.getLogger[F]

    planPath.flatTraverse { path =>
      seedlist match {
        case None => RecoveryPlanConfiguredWithoutSeedlist.raiseError[F, Option[Verified]]
        case Some(allowedPeers) =>
          for {
            content <- Files[F]
              .readAll(path)
              .through(text.utf8.decode)
              .compile
              .string
              .adaptError { case error => RecoveryPlanFileUnreadable(path.toString, error.getMessage) }
            signed <- decode[Signed[Gl0RecoveryPlan]](content)
              .leftMap(error => RecoveryPlanFileUnreadable(path.toString, error.getMessage): Throwable)
              .liftTo[F]
            _ <- role match {
              case Role.RollbackLead(nodeId, _) =>
                Gl0RecoveryPlan.LeadMismatch(nodeId, signed.value.lead).raiseError[F, Unit].unlessA(signed.value.lead === nodeId)
              case Role.PlannedValidator(nodeId) =>
                Gl0RecoveryPlan
                  .InvalidCommittee(s"configured validator=${nodeId.value.value} is not in the planned committee")
                  .raiseError[F, Unit]
                  .unlessA(signed.value.committee.contains(nodeId)) >>
                  Gl0RecoveryPlan
                    .InvalidCommittee("designated rollback lead must not start in run-validator mode")
                    .raiseError[F, Unit]
                    .whenA(signed.value.lead === nodeId)
            }
            verified <- Gl0RecoveryPlan.verify(
              signedValidator,
              expectedNetwork,
              signed.value.lead,
              role.rollbackHash(signed),
              allowedPeers,
              allowanceList,
              maxFacilitatorCount,
              quorumThresholdFraction,
              signed
            )
            plan <- verified.liftTo[F]
            _ <- logger.warn(
              s"[Gl0RecoveryPlan] VERIFIED operator recovery plan=${plan.planId.value.take(12)} " +
                s"anchor=${plan.anchor.ordinal.show}/${plan.anchor.snapshotHash.value.take(12)} " +
                s"lead=${plan.lead.value.value.take(12)} committee=${plan.committee.size}; " +
                s"role=${role.getClass.getSimpleName.stripSuffix("$")} this node will hold the first round without a timeout escape"
            )
          } yield Verified(signed).some
      }
    }
  }
}
