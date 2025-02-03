package io.constellationnetwork.node.shared.domain.node

import cats.data.NonEmptyChain
import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.node.shared.domain.node.UpdateNodeParametersValidator.UpdateNodeParametersValidationError
import io.constellationnetwork.schema.GlobalSnapshotInfo
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.security.signature.Signed

trait UpdateNodeParametersAcceptanceManager[F[_]] {

  def acceptUpdateNodeParameters(
    signed: List[Signed[UpdateNodeParameters]],
    lastSnapshotContext: GlobalSnapshotInfo
  ): F[UpdateNodeParametersAcceptanceResult]

}

object UpdateNodeParametersAcceptanceManager {
  def make[F[_]: Async](validator: UpdateNodeParametersValidator[F]): UpdateNodeParametersAcceptanceManager[F] =
    new UpdateNodeParametersAcceptanceManager[F] {
      override def acceptUpdateNodeParameters(
        signed: List[Signed[UpdateNodeParameters]],
        lastSnapshotContext: GlobalSnapshotInfo
      ): F[UpdateNodeParametersAcceptanceResult] =
        for {
          validated <- signed.traverse(validator.validate(_, lastSnapshotContext))
          (accepted, notAccepted) = validated
            .zip(signed)
            .foldLeft(
              (
                List.empty[Signed[UpdateNodeParameters]],
                List.empty[(Signed[UpdateNodeParameters], NonEmptyChain[UpdateNodeParametersValidationError])]
              )
            ) {
              case ((accepted, notAccepted), (validated, signed)) =>
                validated match {
                  case Valid(a)   => (a :: accepted, notAccepted)
                  case Invalid(e) => (accepted, (signed, e) :: notAccepted)
                }
            }
        } yield UpdateNodeParametersAcceptanceResult(accepted = accepted, notAccepted = notAccepted)
    }
}
