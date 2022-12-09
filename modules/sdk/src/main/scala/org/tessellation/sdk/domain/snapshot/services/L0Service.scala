package org.tessellation.sdk.domain.snapshot.services

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._

import org.tessellation.dag.snapshot.{GlobalSnapshot, SnapshotOrdinal}
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.L0Peer
import org.tessellation.sdk.domain.cluster.storage.L0ClusterStorage
import org.tessellation.sdk.domain.snapshot.storage.LastGlobalSnapshotStorage
import org.tessellation.sdk.http.p2p.clients.L0GlobalSnapshotClient
import org.tessellation.security.{Hashed, SecurityProvider}

import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait L0Service[F[_]] {
  def pullGlobalSnapshots: F[List[Hashed[GlobalSnapshot]]]
  def pullMajorityGlobalSnapshots: F[List[Hashed[GlobalSnapshot]]]
  def pullMajorityGlobalSnapshot(ordinal: SnapshotOrdinal): F[Option[Hashed[GlobalSnapshot]]]
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

      def fetchOrdinals(peer: L0Peer): F[List[SnapshotOrdinal]] =
        lastSnapshotStorage.getOrdinal.flatMap { lastStoredOrdinal =>
          l0GlobalSnapshotClient.getLatestOrdinal
            .run(peer)
            .map { latestOrdinal =>
              val nextOrdinal = lastStoredOrdinal.map(_.next).getOrElse(latestOrdinal).value.value
              val latestOrdinalCap = latestOrdinal.value.value
                .min(singlePullLimit.map(nextOrdinal + _.value).getOrElse(latestOrdinal.value.value))

              nextOrdinal to latestOrdinalCap
            }
        }
          .map(_.toList.map(o => SnapshotOrdinal(NonNegLong.unsafeFrom(o))))

      def validateGlobalSnapshot(snapshot: Hashed[GlobalSnapshot]): F[Either[Throwable, Hashed[GlobalSnapshot]]] =
        snapshot.asRight[Throwable].pure[F]

      def fetchMajorityGlobalSnapshot(ordinal: SnapshotOrdinal, peers: NonEmptyList[L0Peer]): F[Option[Hashed[GlobalSnapshot]]] =
        peers
          .traverse(fetchGlobalSnapshot(ordinal))
          .map(_.toList.flatten)
          .map(_.sortBy(-_.signed.proofs.length))
          .flatMap { sorted =>
            def go(snapshots: List[Hashed[GlobalSnapshot]]): F[Option[Hashed[GlobalSnapshot]]] =
              snapshots match {
                case majority :: tail =>
                  validateGlobalSnapshot(majority).flatMap {
                    _.fold(_ => go(tail), _ => majority.some.pure[F])
                  }
                case Nil => none[Hashed[GlobalSnapshot]].pure[F]
              }

            go(sorted)
          }

      def fetchGlobalSnapshot(ordinal: SnapshotOrdinal)(peer: L0Peer): F[Option[Hashed[GlobalSnapshot]]] =
        l0GlobalSnapshotClient
          .get(ordinal)(peer)
          .flatMap(_.toHashedWithSignatureCheck)
          .flatMap(_.liftTo[F])
          .map(_.some)
          .handleErrorWith { error =>
            logger.warn(error)(s"Cannot fetch snapshot ordinal=${ordinal.show} from peer id=${peer.id.show}") >>
              none[Hashed[GlobalSnapshot]].pure[F]
          }

      def pullGlobalSnapshots: F[List[Hashed[GlobalSnapshot]]] =
        l0ClusterStorage.getRandomPeer.flatMap { peer =>
          fetchOrdinals(peer).flatMap { ordinals =>
            (ordinals, List.empty[Hashed[GlobalSnapshot]]).tailRecM {
              case (ordinal :: nextOrdinals, snapshots) =>
                fetchGlobalSnapshot(ordinal)(peer)
                  .map(
                    _.fold(snapshots.asRight[(List[SnapshotOrdinal], List[Hashed[GlobalSnapshot]])]) { s =>
                      (nextOrdinals, snapshots :+ s).asLeft[List[Hashed[GlobalSnapshot]]]
                    }
                  )

              case (Nil, snapshots) =>
                Applicative[F].pure(snapshots.asRight[(List[SnapshotOrdinal], List[Hashed[GlobalSnapshot]])])
            }
          }
        }.handleErrorWith { e =>
          logger.warn(e)(s"Failure pulling global snapshots!") >>
            Applicative[F].pure(List.empty[Hashed[GlobalSnapshot]])
        }

      def pullMajorityGlobalSnapshots: F[List[Hashed[GlobalSnapshot]]] =
        l0ClusterStorage.getPeers
          .map(_.toNonEmptyList)
          .flatMap { peers =>
            fetchOrdinals(peers.head).flatMap { ordinals =>
              (ordinals, List.empty[Hashed[GlobalSnapshot]]).tailRecM {
                case (ordinal :: nextOrdinals, snapshots) =>
                  fetchMajorityGlobalSnapshot(ordinal, peers)
                    .map(
                      _.fold(snapshots.asRight[(List[SnapshotOrdinal], List[Hashed[GlobalSnapshot]])]) { s =>
                        (nextOrdinals, snapshots :+ s).asLeft[List[Hashed[GlobalSnapshot]]]
                      }
                    )

                case (Nil, snapshots) =>
                  Applicative[F].pure(snapshots.asRight[(List[SnapshotOrdinal], List[Hashed[GlobalSnapshot]])])
              }
            }
          }
          .handleErrorWith { e =>
            logger.warn(e)(s"Failure pulling global snapshots!") >>
              Applicative[F].pure(List.empty[Hashed[GlobalSnapshot]])
          }

      def pullMajorityGlobalSnapshot(ordinal: SnapshotOrdinal): F[Option[Hashed[GlobalSnapshot]]] =
        l0ClusterStorage.getPeers
          .map(_.toNonEmptyList)
          .flatMap(fetchMajorityGlobalSnapshot(ordinal, _))

      def pullGlobalSnapshot(ordinal: SnapshotOrdinal): F[Option[Hashed[GlobalSnapshot]]] =
        l0ClusterStorage.getRandomPeer.flatMap {
          fetchGlobalSnapshot(ordinal)
        }
    }
}
