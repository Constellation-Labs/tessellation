package io.constellationnetwork.node.shared.domain.priceOracle

import cats.data.ValidatedNec
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.node.shared.domain.priceOracle.PricingUpdateValidator.PricingUpdateValidationErrorOr
import io.constellationnetwork.schema.GlobalSnapshotInfo
import io.constellationnetwork.schema.artifact.PricingUpdate
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive

trait PricingUpdateValidator[F[_]] {
  def validate(
    signed: Signed[PricingUpdate],
    lastContext: GlobalSnapshotInfo
  ): F[PricingUpdateValidationErrorOr[Signed[PricingUpdate]]]
}

object PricingUpdateValidator {
  def make[F[_]: Async](allowedNodes: Option[List[PeerId]]): PricingUpdateValidator[F] = new PricingUpdateValidator[F] {
    def validate(
      signed: Signed[PricingUpdate],
      lastContext: GlobalSnapshotInfo
    ): F[PricingUpdateValidationErrorOr[Signed[PricingUpdate]]] =
      validatePeerId(signed).pure[F]

    private def validatePeerId(signed: Signed[PricingUpdate]): PricingUpdateValidationErrorOr[Signed[PricingUpdate]] = {
      val peerId = signed.proofs.head.id.toPeerId
      if (allowedNodes.isEmpty || allowedNodes.exists(_.contains(peerId))) {
        signed.validNec[PricingUpdateValidationError]
      } else {
        UnauthorizedNode(peerId).invalidNec
      }
    }
  }

  @derive(eqv, show)
  sealed trait PricingUpdateValidationError

  case class UnauthorizedNode(nodeId: PeerId) extends PricingUpdateValidationError

  type PricingUpdateValidationErrorOr[A] = ValidatedNec[PricingUpdateValidationError, A]
}
