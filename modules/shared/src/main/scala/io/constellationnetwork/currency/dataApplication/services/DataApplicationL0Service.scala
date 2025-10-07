package io.constellationnetwork.currency.dataApplication.services

import cats.Applicative
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedSet

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.context.L0NodeContext
import io.constellationnetwork.currency.dataApplication.ops.DataApplicationL0ContextualOps
import io.constellationnetwork.currency.dataApplication.plugin.PluginRegistry
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.schema.artifact.TokenUnlock
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import io.constellationnetwork.security._
import io.constellationnetwork.syntax.sortedCollection.sortedSetSyntax

trait DataApplicationL0Service[F[_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState]
    extends DataApplicationService[F, D, DON, DOF]
    with DataApplicationL0ContextualOps[F, D, DON, DOF] {
  def genesis: DataState[DON, DOF]

  def onSnapshotConsensusResult(snapshot: Hashed[CurrencyIncrementalSnapshot])(implicit A: Applicative[F]): F[Unit] = A.unit

  def onGlobalSnapshotPull(snapshot: Hashed[GlobalIncrementalSnapshot], context: GlobalSnapshotInfo)(implicit A: Applicative[F]): F[Unit] =
    A.unit

  def getTokenUnlocks(
    state: DataState[DataOnChainState, DataCalculatedState]
  )(implicit context: L0NodeContext[F], async: Async[F], hasher: Hasher[F]): F[SortedSet[TokenUnlock]] = for {
    maybeLastSynchronizedGlobalSnapshot <- context.getLastSynchronizedGlobalSnapshot
    maybeLastCurrencySnapshotCombined <- context.getLastCurrencySnapshotCombined

    maybeLastGlobalEpochProgress = maybeLastSynchronizedGlobalSnapshot.map(_.epochProgress)
    expiredTokenLocks = maybeLastGlobalEpochProgress.flatMap { lastGlobalEpochProgress =>
      maybeLastCurrencySnapshotCombined.map {
        case (_, state) =>
          state.activeTokenLocks.collect { activeTokenLocks =>
            activeTokenLocks.values.flatten.toList
              .filter(_.unlockEpoch.exists(_ <= lastGlobalEpochProgress))
          }.getOrElse(List.empty)
      }
    }.getOrElse(List.empty)

    result <- expiredTokenLocks.traverse { tokenLock =>
      tokenLock.toHashed.map { tokenLockRef =>
        TokenUnlock(
          tokenLockRef.hash,
          tokenLock.amount,
          tokenLock.currencyId,
          tokenLock.source
        )
      }
    }
  } yield result.toSortedSet

  def pluginRegistry: Option[PluginRegistry[F, DataUpdate, DataOnChainState, DataCalculatedState]] = None
}
