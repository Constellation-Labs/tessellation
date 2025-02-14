package io.constellationnetwork.node.shared.infrastructure.snapshot.storage

import cats.effect.kernel.Async
import cats.implicits.{catsSyntaxFlatten, toTraverseOps}
import cats.syntax.functor._
import cats.{Applicative, MonadThrow}

import scala.collection.immutable.SortedMap

import io.constellationnetwork.node.shared.domain.collateral.LatestBalances
import io.constellationnetwork.node.shared.domain.snapshot.Validator.isNextSnapshot
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastNGlobalSnapshotStorage
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.height.Height
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.Hashed

import fs2.Stream
import fs2.concurrent.SignallingRef

object LastNGlobalSnapshotStorage {

  def make[F[_]: Async]: F[LastNGlobalSnapshotStorage[F] with LatestBalances[F]] =
    SignallingRef.of[F, SortedMap[SnapshotOrdinal, (Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]](SortedMap.empty).map(make(_))

  def make[F[_]: Async](
    snapshotsR: SignallingRef[F, SortedMap[SnapshotOrdinal, (Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]]
  ): LastNGlobalSnapshotStorage[F] with LatestBalances[F] =
    new LastNGlobalSnapshotStorage[F] with LatestBalances[F] {

      def set(snapshot: Hashed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): F[Unit] =
        snapshotsR.modify { snapshots =>
          snapshots.lastOption match {
            case Some((_, (latest, _))) if isNextSnapshot(latest, snapshot.signed.value) =>
              (snapshots.updated(snapshot.ordinal, (snapshot, state)), Applicative[F].unit)
            case _ => (snapshots, MonadThrow[F].raiseError[Unit](new Throwable("Failure during putting new global snapshot!")))
          }
        }.flatten

      def setInitial(snapshot: Hashed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): F[Unit] =
        snapshotsR.modify { snapshots =>
          if (snapshots.nonEmpty) {
            (snapshots, MonadThrow[F].raiseError[Unit](new Throwable(s"Failure putting initial snapshot! Storage non empty.")))
          } else {
            (snapshots.updated(snapshot.ordinal, (snapshot, state)), Applicative[F].unit)
          }
        }.flatten

      def get: F[Option[Hashed[GlobalIncrementalSnapshot]]] = getCombined.map(_.map { case (snapshot, _) => snapshot })

      def get(ordinal: SnapshotOrdinal): F[Option[Hashed[GlobalIncrementalSnapshot]]] =
        snapshotsR.get.map(_.get(ordinal).map { case (snapshot, _) => snapshot })

      def getCombined: F[Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] = snapshotsR.get.map {
        _.lastOption.map { case (_, combined) => combined }
      }

      def getCombinedStream: fs2.Stream[F, Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] =
        Stream
          .eval[F, Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]](getCombined)
          .merge(snapshotsR.discrete.map(_.lastOption.map { case (_, combined) => combined }))

      def getOrdinal: F[Option[SnapshotOrdinal]] =
        get.map(_.map(_.ordinal))

      def getHeight: F[Option[Height]] =
        get.map(_.map(_.height))

      def getLatestBalances: F[Option[Map[Address, Balance]]] =
        snapshotsR.get.map(_.headOption.map(_._2._2.balances.toMap))

      def getLatestBalancesStream: Stream[F, Map[Address, Balance]] =
        snapshotsR.discrete
          .evalMap(snapshotMap => Async[F].pure(snapshotMap.headOption.map(_._2)))
          .collect { case Some(snapshot) => snapshot }
          .map(_._2.balances)

      def getLastN(ordinal: SnapshotOrdinal, n: Int): F[Option[List[Hashed[GlobalIncrementalSnapshot]]]] = {
        val ordinalsToFetch = (0 until n)
          .flatMap(i => SnapshotOrdinal(ordinal.value.value - i))
          .toList

        ordinalsToFetch.traverse(get).map { results =>
          val snapshots = results.flatten

          if (snapshots.isEmpty) None
          else Some(snapshots)
        }
      }
    }
}
