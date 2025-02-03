package io.constellationnetwork.node.shared.domain.node

import cats.data.ValidatedNec
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.ext.cats.syntax.validated._
import io.constellationnetwork.ext.crypto.RefinedHasher
import io.constellationnetwork.node.shared.domain.node.UpdateNodeParametersValidator.UpdateNodeParametersValidationErrorOr
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.node.{RewardFraction, UpdateNodeParameters, UpdateNodeParametersReference}
import io.constellationnetwork.schema.{GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.SignedValidator.SignedValidationError
import io.constellationnetwork.security.signature.{Signed, SignedValidator}

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.cats.refTypeOrder

trait UpdateNodeParametersValidator[F[_]] {

  def validate(
    signed: Signed[UpdateNodeParameters],
    lastSnapshotContext: GlobalSnapshotInfo
  ): F[UpdateNodeParametersValidationErrorOr[Signed[UpdateNodeParameters]]]

}

object UpdateNodeParametersValidator {

  def make[F[_]: Async: Hasher](
    signedValidator: SignedValidator[F],
    minRewardValue: RewardFraction,
    maxRewardValue: RewardFraction
  ): UpdateNodeParametersValidator[F] =
    new UpdateNodeParametersValidator[F] {
      override def validate(
        signed: Signed[UpdateNodeParameters],
        lastSnapshotContext: GlobalSnapshotInfo
      ): F[UpdateNodeParametersValidationErrorOr[Signed[UpdateNodeParameters]]] =
        for {
          signaturesV <- signedValidator.validateSignatures(signed).map(_.errorMap[UpdateNodeParametersValidationError](InvalidSigned))
          parentV <- validateParent(signed, lastSnapshotContext)
          rewardFractionV = validateRewardFraction(signed)
        } yield signaturesV.productR(parentV).productR(rewardFractionV)

      private def validateRewardFraction(
        signed: Signed[UpdateNodeParameters]
      ): UpdateNodeParametersValidationErrorOr[Signed[UpdateNodeParameters]] =
        if (
          signed.delegatedStakeRewardParameters.rewardFraction >= minRewardValue &&
          signed.delegatedStakeRewardParameters.rewardFraction <= maxRewardValue
        ) {
          signed.validNec[UpdateNodeParametersValidationError]
        } else {
          InvalidRewardValue(signed.delegatedStakeRewardParameters.rewardFraction.value).invalidNec[Signed[UpdateNodeParameters]]
        }

      private def validateParent(
        signed: Signed[UpdateNodeParameters],
        lastSnapshotContext: GlobalSnapshotInfo
      ): F[UpdateNodeParametersValidationErrorOr[Signed[UpdateNodeParameters]]] = {
        val parent: UpdateNodeParametersReference = signed.value.parent

        def validParent: UpdateNodeParametersValidationErrorOr[Signed[UpdateNodeParameters]] =
          signed.validNec[UpdateNodeParametersValidationError]

        def invalidParent: UpdateNodeParametersValidationErrorOr[Signed[UpdateNodeParameters]] = InvalidParent(parent).invalidNec

        val params =
          lastSnapshotContext.updateNodeParameters.getOrElse(SortedMap.empty[Id, (Signed[UpdateNodeParameters], SnapshotOrdinal)])

        if (params.isEmpty) {
          if (parent == UpdateNodeParametersReference.empty) {
            validParent.pure[F]
          } else {
            invalidParent.pure[F]
          }
        } else {
          params.values.toList.filter {
            case (signed, _) =>
              signed.value.ordinal == parent.ordinal
          }.traverse {
            case (signed, _) =>
              signed.value.hash.map(h => UpdateNodeParametersReference(signed.value.ordinal, h))
          }.map { refs =>
            refs.find(_ == parent) match {
              case Some(_) => validParent
              case None    => invalidParent
            }
          }
        }
      }
    }

  @derive(eqv, show)
  sealed trait UpdateNodeParametersValidationError
  case class InvalidSigned(error: SignedValidationError) extends UpdateNodeParametersValidationError
  case class InvalidRewardValue(rewardFraction: Int) extends UpdateNodeParametersValidationError
  case class InvalidParent(parent: UpdateNodeParametersReference) extends UpdateNodeParametersValidationError

  type UpdateNodeParametersValidationErrorOr[A] = ValidatedNec[UpdateNodeParametersValidationError, A]
}
