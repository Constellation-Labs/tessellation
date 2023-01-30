package org.tessellation.dag.snapshot

import cats.data.NonEmptyList
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.functor._
import cats.{Applicative, Monad, Traverse}

import scala.collection.SortedMap
import scala.collection.immutable.SortedSet

import org.tessellation.dag.domain.block.DAGBlockAsActiveTip
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction.RewardTransaction
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.StateChannelSnapshotBinary

import higherkindness.droste.util.DefaultTraverse
import higherkindness.droste.{AlgebraM, CoalgebraM, scheme}

sealed trait StackF[A]

case class More[A](a: A, step: GlobalSnapshotTraverse.GlobalSnapshotTraverseStep) extends StackF[A]
case class Done[A](result: GlobalSnapshotInfo) extends StackF[A]

object StackF {

  implicit val traverse: Traverse[StackF] = new DefaultTraverse[StackF] {
    override def traverse[G[_]: Applicative, A, B](fa: StackF[A])(f: A => G[B]): G[StackF[B]] =
      fa match {
        case More(a, step) => f(a).map(More(_, step))
        case Done(r)       => (Done(r): StackF[B]).pure[G]
      }
  }
}

trait GlobalSnapshotTraverse[F[_]] {
  def computeState(latest: IncrementalGlobalSnapshot): F[GlobalSnapshotInfo]
}

object GlobalSnapshotTraverse {

  case class GlobalSnapshotTraverseStep(
    blocks: SortedSet[DAGBlockAsActiveTip],
    stateChannelSnapshots: SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
    rewards: SortedSet[RewardTransaction]
  )

  def loadGlobalSnapshotCoalgebra[F[_]: Monad](
    loadGlobalSnapshotFn: Hash => F[Either[GlobalSnapshot, IncrementalGlobalSnapshot]]
  ): CoalgebraM[F, StackF, Either[GlobalSnapshot, IncrementalGlobalSnapshot]] = CoalgebraM {
    case Left(globalSnapshot) => Applicative[F].pure(Done(globalSnapshot.info))
    case Right(incrementalGlobalSnapshot) =>
      def prevHash = incrementalGlobalSnapshot.lastSnapshotHash

      val step = GlobalSnapshotTraverseStep(
        incrementalGlobalSnapshot.blocks,
        incrementalGlobalSnapshot.stateChannelSnapshots,
        incrementalGlobalSnapshot.rewards
      )

      loadGlobalSnapshotFn(prevHash).map(More(_, step))
  }

  def computeStateAlgebra[F[_]: Monad](
    applyGlobalSnapshotFn: (GlobalSnapshotInfo, GlobalSnapshotTraverseStep) => F[GlobalSnapshotInfo]
  ): AlgebraM[F, StackF, GlobalSnapshotInfo] = AlgebraM {
    case Done(info)       => info.pure[F]
    case More(info, step) => applyGlobalSnapshotFn(info, step)
  }

  def make[F[_]: Monad](
    loadGlobalSnapshotFn: Hash => F[Either[GlobalSnapshot, IncrementalGlobalSnapshot]]
  ): GlobalSnapshotTraverse[F] =
    new GlobalSnapshotTraverse[F] {

      def applyGlobalSnapshot(
        state: GlobalSnapshotInfo,
        step: GlobalSnapshotTraverseStep
      ): F[GlobalSnapshotInfo] =
        state.pure[F] // TODO: apply step to aggregated state

      def computeState(latest: IncrementalGlobalSnapshot): F[GlobalSnapshotInfo] =
        scheme
          .hyloM(computeStateAlgebra(applyGlobalSnapshot), loadGlobalSnapshotCoalgebra(loadGlobalSnapshotFn))
          .apply(latest.asRight[GlobalSnapshot])
    }

}
