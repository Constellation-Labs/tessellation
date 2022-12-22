package org.tessellation.sdk.domain.snapshot.services

import cats.Applicative
import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._

import org.tessellation.dag.snapshot.GlobalSnapshot
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.security.{Hashed, SecurityProvider}
import org.tessellation.sdk.domain.cluster.storage.L0ClusterStorage
import org.tessellation.sdk.domain.snapshot.storage.LastGlobalSnapshotStorage
import org.tessellation.sdk.http.p2p.clients.L0GlobalSnapshotClient

import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait L0Service[F[_]] {
  def pullGlobalSnapshots: F[List[Hashed[GlobalSnapshot]]]
  def pullGlobalSnapshot(ordinal: SnapshotOrdinal): F[Option[Hashed[GlobalSnapshot]]]
}

object L0Service {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    l0GlobalSnapshotClient: L0GlobalSnapshotClient[F],
    l0ClusterStorage: L0ClusterStorage[F],
    lastSnapshotStorage: LastGlobalSnapshotStorage[F],
    singlePullLimit: Option[PosLong]
  ): L0Service[F] =
    new L0Service[F] {

      private val logger = Slf4jLogger.getLogger[F]

      def pullGlobalSnapshot(ordinal: SnapshotOrdinal): F[Option[Hashed[GlobalSnapshot]]] =
        l0ClusterStorage.getRandomPeer.flatMap { l0Peer =>
          l0GlobalSnapshotClient
            .get(ordinal)(l0Peer)
            .flatMap(_.toHashedWithSignatureCheck)
            .flatMap(_.liftTo[F])
            .map(_.some)
        }.handleErrorWith { e =>
          logger
            .warn(e)(s"Failure pulling single snapshot with ordinal=$ordinal")
            .map(_ => none[Hashed[GlobalSnapshot]])
        }

      def pullGlobalSnapshots: F[List[Hashed[GlobalSnapshot]]] = {
        for {
          lastStoredOrdinal <- lastSnapshotStorage.getOrdinal
          l0Peer <- l0ClusterStorage.getRandomPeer

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
              (ordinals, List.empty[Hashed[GlobalSnapshot]]).tailRecM {
                case (ordinal :: nextOrdinals, snapshots) =>
                  l0GlobalSnapshotClient
                    .get(ordinal)(l0Peer)
                    .flatMap(_.toHashedWithSignatureCheck)
                    .flatMap(_.liftTo[F])
                    .map(s => (nextOrdinals, snapshots :+ s).asLeft[List[Hashed[GlobalSnapshot]]])
                    .handleErrorWith { e =>
                      logger
                        .warn(e)(s"Failure pulling snapshot with ordinal=$ordinal")
                        .map(_ => snapshots.asRight[(List[SnapshotOrdinal], List[Hashed[GlobalSnapshot]])])
                    }

                case (Nil, snapshots) =>
                  Applicative[F].pure(snapshots.asRight[(List[SnapshotOrdinal], List[Hashed[GlobalSnapshot]])])
              }
            }
        } yield pulled
      }.handleErrorWith { e =>
        logger.warn(e)(s"Failure pulling global snapshots!") >>
          Applicative[F].pure(List.empty[Hashed[GlobalSnapshot]])
      }
    }
}
