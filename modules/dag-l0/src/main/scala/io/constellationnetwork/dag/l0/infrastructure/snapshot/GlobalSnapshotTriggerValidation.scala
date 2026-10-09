package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.Monad
import cats.syntax.all._

import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.node.shared.domain.consensus.ConsensusFunctions.InvalidArtifact
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import io.constellationnetwork.schema.epoch.EpochProgress

private[snapshot] object GlobalSnapshotTriggerValidation {

  final case class EpochProgressMismatch(expected: EpochProgress, actual: EpochProgress) extends InvalidArtifact

  def whenValid[F[_]: Monad, A](parent: EpochProgress, trigger: ConsensusTrigger, candidate: EpochProgress)(
    onInvalid: EpochProgressMismatch => F[A]
  )(onValid: => F[A]): F[A] = {
    val expected = trigger match {
      case EventTrigger => parent
      case TimeTrigger  => parent.next
    }

    (candidate === expected)
      .pure[F]
      .ifM(
        ifTrue = onValid,
        ifFalse = onInvalid(EpochProgressMismatch(expected, candidate))
      )
  }
}
