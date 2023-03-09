package org.tessellation.sdk.domain.snapshot.services

import cats.Applicative
import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._

import org.tessellation.ext.cats.syntax.next._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.{GlobalSnapshotInfo, IncrementalGlobalSnapshot, SnapshotOrdinal}
import org.tessellation.sdk.domain.cluster.storage.L0ClusterStorage
import org.tessellation.sdk.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.sdk.http.p2p.clients.L0GlobalSnapshotClient
import org.tessellation.security.{Hashed, SecurityProvider}

import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait GlobalL0Service[F[_]] {
  def pullLatestSnapshot: F[(Hashed[IncrementalGlobalSnapshot], GlobalSnapshotInfo)]
  def pullGlobalSnapshots: F[Either[(Hashed[IncrementalGlobalSnapshot], GlobalSnapshotInfo), List[Hashed[IncrementalGlobalSnapshot]]]]
  def pullGlobalSnapshot(ordinal: SnapshotOrdinal): F[Option[Hashed[IncrementalGlobalSnapshot]]]
}

object GlobalL0Service {

  def make[
    F[_]: Async: KryoSerializer: SecurityProvider
  ](
    l0GlobalSnapshotClient: L0GlobalSnapshotClient[F],
    globalL0ClusterStorage: L0ClusterStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, IncrementalGlobalSnapshot, GlobalSnapshotInfo],
    singlePullLimit: Option[PosLong]
  ): GlobalL0Service[F] =
    new GlobalL0Service[F] {

      private val logger = Slf4jLogger.getLogger[F]

      def pullLatestSnapshot: F[(Hashed[IncrementalGlobalSnapshot], GlobalSnapshotInfo)] =
        globalL0ClusterStorage.getRandomPeer.flatMap { l0Peer =>
          l0GlobalSnapshotClient.getLatest(l0Peer).flatMap {
            case ((snapshot, state)) =>
              snapshot.toHashedWithSignatureCheck.flatMap(_.liftTo[F]).map((_, state))
          }
        }

      def pullGlobalSnapshot(ordinal: SnapshotOrdinal): F[Option[Hashed[IncrementalGlobalSnapshot]]] =
        globalL0ClusterStorage.getRandomPeer.flatMap { l0Peer =>
          l0GlobalSnapshotClient
            .get(ordinal)(l0Peer)
            .flatMap(_.toHashedWithSignatureCheck.flatMap(_.liftTo[F]))
            .map(_.some)
        }.handleErrorWith { e =>
          logger
            .warn(e)(s"Failure pulling single snapshot with ordinal=$ordinal")
            .map(_ => none[Hashed[IncrementalGlobalSnapshot]])
        }

      def pullGlobalSnapshots: F[Either[(Hashed[IncrementalGlobalSnapshot], GlobalSnapshotInfo), List[Hashed[IncrementalGlobalSnapshot]]]] =
        lastGlobalSnapshotStorage.getOrdinal.flatMap {
          _.fold {
            pullLatestSnapshot.map(_.asLeft[List[Hashed[IncrementalGlobalSnapshot]]])
          } { lastStoredOrdinal =>
            def pulled = globalL0ClusterStorage.getRandomPeer.flatMap { l0Peer =>
              l0GlobalSnapshotClient.getLatestOrdinal
                .run(l0Peer)
                .map { lastOrdinal =>
                  val nextOrdinal = lastStoredOrdinal.next
                  val lastOrdinalCap = lastOrdinal.value.value
                    .min(singlePullLimit.map(nextOrdinal.value.value + _.value).getOrElse(lastOrdinal.value.value))

                  nextOrdinal.value.value to lastOrdinalCap
                }
                .map(_.toList.map(o => SnapshotOrdinal(NonNegLong.unsafeFrom(o))))
                .flatMap { ordinals =>
                  (ordinals, List.empty[Hashed[IncrementalGlobalSnapshot]]).tailRecM {
                    case (ordinal :: nextOrdinals, snapshots) =>
                      l0GlobalSnapshotClient
                        .get(ordinal)(l0Peer)
                        .flatMap(_.toHashedWithSignatureCheck.flatMap(_.liftTo[F]))
                        .map(s => (nextOrdinals, snapshots :+ s).asLeft[List[Hashed[IncrementalGlobalSnapshot]]])
                        .handleErrorWith { e =>
                          logger
                            .warn(e)(s"Failure pulling snapshot with ordinal=$ordinal")
                            .map(_ => snapshots.asRight[(List[SnapshotOrdinal], List[Hashed[IncrementalGlobalSnapshot]])])
                        }

                    case (Nil, snapshots) =>
                      Applicative[F].pure(snapshots.asRight[(List[SnapshotOrdinal], List[Hashed[IncrementalGlobalSnapshot]])])
                  }
                }
            }

            pulled.map(_.asRight[(Hashed[IncrementalGlobalSnapshot], GlobalSnapshotInfo)])
          }
        }.handleErrorWith { e =>
          logger.warn(e)(s"Failure pulling global snapshots!") >>
            Applicative[F].pure(
              List.empty[Hashed[IncrementalGlobalSnapshot]].asRight[(Hashed[IncrementalGlobalSnapshot], GlobalSnapshotInfo)]
            )
        }
    }
}
