package io.constellationnetwork.currency.schema

import cats.Order._
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.ext.cats.data.OrderBasedOrdering
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.ext.derevo.ordering
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.cluster.SessionToken
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.auto.{autoRefineV, autoUnwrap, _}
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.refined._
import io.estatico.newtype.macros.newtype

object globalSnapshotSync {

  @derive(decoder, encoder, order, ordering, show)
  case class GlobalSnapshotSync(
    parentOrdinal: GlobalSnapshotSyncOrdinal,
    globalSnapshotOrdinal: SnapshotOrdinal,
    globalSnapshotHash: Hash,
    session: SessionToken
  ) {
    def ordinal: GlobalSnapshotSyncOrdinal = parentOrdinal.next
  }

  object GlobalSnapshotSync {
    implicit object OrderingInstance extends OrderBasedOrdering[GlobalSnapshotSync]
  }

  @derive(decoder, encoder, order, ordering, show)
  case class GlobalSyncView(
    ordinal: SnapshotOrdinal,
    hash: Hash,
    epochProgress: EpochProgress
  )

  object GlobalSyncView {
    def empty: GlobalSyncView = GlobalSyncView(
      SnapshotOrdinal.MinValue,
      Hash.empty,
      EpochProgress.MinValue
    )
  }

  @derive(decoder, encoder, show, order, ordering)
  @newtype
  case class GlobalSnapshotSyncOrdinal(value: NonNegLong) {
    def next: GlobalSnapshotSyncOrdinal = GlobalSnapshotSyncOrdinal(value |+| 1L)
  }

  object GlobalSnapshotSyncOrdinal {
    val first: GlobalSnapshotSyncOrdinal = GlobalSnapshotSyncOrdinal(1L)

    val MinValue: GlobalSnapshotSyncOrdinal = GlobalSnapshotSyncOrdinal(NonNegLong.MinValue)
  }

  @derive(decoder, encoder, order, show)
  case class GlobalSnapshotSyncReference(ordinal: GlobalSnapshotSyncOrdinal, hash: Hash)

  object GlobalSnapshotSyncReference {
    val empty: GlobalSnapshotSyncReference = GlobalSnapshotSyncReference(GlobalSnapshotSyncOrdinal.MinValue, Hash.empty)

    def of[F[_]: Async: Hasher](globalSnapshotSync: Signed[GlobalSnapshotSync]): F[GlobalSnapshotSyncReference] =
      globalSnapshotSync.value.hash.map(GlobalSnapshotSyncReference(globalSnapshotSync.ordinal, _))

    def of(globalSnapshotSync: Hashed[GlobalSnapshotSync]): GlobalSnapshotSyncReference =
      GlobalSnapshotSyncReference(globalSnapshotSync.ordinal, globalSnapshotSync.hash)
  }
}
