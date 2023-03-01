package org.tessellation.sdk.domain.snapshot.services

import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.{Applicative, Eq}

import org.tessellation.ext.cats.syntax.next._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo}
import org.tessellation.schema.transaction.Transaction
import org.tessellation.schema.{Block, SnapshotOrdinal}
import org.tessellation.sdk.domain.cluster.storage.L0ClusterStorage
import org.tessellation.sdk.domain.http.p2p.SnapshotClient
import org.tessellation.sdk.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.security.{Hashed, SecurityProvider}

import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait GlobalL0Service[F[_], T <: Transaction, B <: Block[T], S <: Snapshot[T, B], SI <: SnapshotInfo] {
  def pullGlobalSnapshots: F[List[(Hashed[S], SI)]]
  def pullGlobalSnapshot(ordinal: SnapshotOrdinal): F[Option[(Hashed[S], SI)]]
}

object GlobalL0Service {

  def make[
    F[_]: Async: KryoSerializer: SecurityProvider,
    T <: Transaction: Eq,
    B <: Block[T]: Eq: Ordering,
    S <: Snapshot[T, B],
    SI <: SnapshotInfo
  ](
    l0GlobalSnapshotClient: L0GlobalSnapshotClient[F, T, B, S, SI],
    globalL0ClusterStorage: L0ClusterStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, S, SI],
    singlePullLimit: Option[PosLong]
  ): GlobalL0Service[F, T, B, S, SI] =
    new GlobalL0Service[F, T, B, S, SI] {

      private val logger = Slf4jLogger.getLogger[F]

      def pullGlobalSnapshot(ordinal: SnapshotOrdinal): F[Option[(Hashed[S], SI)]] =
        globalL0ClusterStorage.getRandomPeer.flatMap { l0Peer =>
          l0GlobalSnapshotClient
            .get(ordinal)(l0Peer)
            .flatMap {
              case ((snapshot, state)) =>
                snapshot.toHashedWithSignatureCheck.flatMap(_.liftTo[F]).map((_, state))
            }
            .map(_.some)
        }.handleErrorWith { e =>
          logger
            .warn(e)(s"Failure pulling single snapshot with ordinal=$ordinal")
            .map(_ => none[(Hashed[S], SI)])
        }

      def pullGlobalSnapshots: F[List[(Hashed[S], SI)]] = {
        for {
          lastStoredOrdinal <- lastGlobalSnapshotStorage.getOrdinal
          l0Peer <- globalL0ClusterStorage.getRandomPeer

          pulled <- l0GlobalSnapshotClient.getLatestOrdinal
            .run(l0Peer)
            .map { lastOrdinal =>
              val nextOrdinal = lastStoredOrdinal.map(_.next).getOrElse(lastOrdinal)
              val lastOrdinalCap = lastOrdinal.value.value
                .min(singlePullLimit.map(nextOrdinal.value.value + _.value).getOrElse(lastOrdinal.value.value))

              nextOrdinal.value.value to lastOrdinalCap
            }
            .map(_.toList.map(o => SnapshotOrdinal(NonNegLong.unsafeFrom(o))))
            .flatMap { ordinals =>
              (ordinals, List.empty[(Hashed[S], SI)]).tailRecM {
                case (ordinal :: nextOrdinals, snapshots) =>
                  l0GlobalSnapshotClient
                    .get(ordinal)(l0Peer)
                    .flatMap {
                      case ((snapshot, state)) =>
                        snapshot.toHashedWithSignatureCheck.flatMap(_.liftTo[F]).map((_, state))
                    }
                    .map(s => (nextOrdinals, snapshots :+ s).asLeft[List[(Hashed[S], SI)]])
                    .handleErrorWith { e =>
                      logger
                        .warn(e)(s"Failure pulling snapshot with ordinal=$ordinal")
                        .map(_ => snapshots.asRight[(List[SnapshotOrdinal], List[(Hashed[S], SI)])])
                    }

                case (Nil, snapshots) =>
                  Applicative[F].pure(snapshots.asRight[(List[SnapshotOrdinal], List[(Hashed[S], SI)])])
              }
            }
        } yield pulled
      }.handleErrorWith { e =>
        logger.warn(e)(s"Failure pulling global snapshots!") >>
          Applicative[F].pure(List.empty[(Hashed[S], SI)])
      }
    }
}
