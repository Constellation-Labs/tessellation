package org.tessellation.infrastructure.snapshot

import cats.data.{Validated, ValidatedNel}
import cats.kernel.Next
import cats.syntax.order._
import cats.syntax.show._

import scala.util.control.NoStackTrace

import org.tessellation.config.types.SnapshotConfig
import org.tessellation.dag.domain.block.{L1Output, Tips}
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.infrastructure.snapshot.SnapshotPreconditionsValidator.{TipsNotHighEnough, ValidationResult}
import org.tessellation.schema.height.Height
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._

class SnapshotPreconditionsValidator[F[_]](config: SnapshotConfig) {

  def validate(previousSnapshotHeight: Height, data: Signed[L1Output])(
    implicit N: Next[Height]
  ): ValidationResult[Height] =
    validateMinTipHeight(previousSnapshotHeight, data.tips)

  def validateMinTipHeight(
    previousSnapshotHeight: Height,
    tips: Option[Tips]
  )(implicit N: Next[Height]): ValidationResult[Height] = {
    val minTipHeight = tips.map(_.value.toList.minBy(_.height).height).getOrElse(Height.MinValue)
    val nextHeight = previousSnapshotHeight.next

    Validated.condNel(
      minTipHeight > nextHeight,
      minTipHeight,
      TipsNotHighEnough(minTipHeight, nextHeight)
    )
  }

}

object SnapshotPreconditionsValidator {
  type ValidationResult[A] = ValidatedNel[SnapshotPreconditionNotMetError, A]

  sealed trait SnapshotPreconditionNotMetError extends NoStackTrace {
    val errorMessage: String
    override def getMessage: String = errorMessage
  }

  case class TipsNotHighEnough(minTipHeight: Height, nextHeight: Height) extends SnapshotPreconditionNotMetError {
    override val errorMessage: String = s"Min tip (${minTipHeight.show}) is below expected height ${nextHeight.show}"
  }
}
