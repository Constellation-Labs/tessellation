package org.tessellation.dag.l1.domain.snapshot.services

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.dag.l1.domain.snapshot.storage.LastGlobalSnapshotOrdinalStorage
import org.tessellation.dag.l1.http.p2p.clients.L0GlobalSnapshotClient
import org.tessellation.dag.snapshot.{GlobalSnapshot, SnapshotOrdinal}
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.sdk.domain.cluster.storage.L0ClusterStorage
import org.tessellation.security.signature.Signed

import eu.timepit.refined.types.numeric.NonNegLong
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait L0Service[F[_]] {
  def pullGlobalSnapshots: F[List[Signed[GlobalSnapshot]]]
}

object L0Service {

  def make[F[_]: Async](
    l0GlobalSnapshotClient: L0GlobalSnapshotClient[F],
    l0ClusterStorage: L0ClusterStorage[F],
    lastOrdinalStorage: LastGlobalSnapshotOrdinalStorage[F]
  ): L0Service[F] =
    new L0Service[F] {

      private val logger = Slf4jLogger.getLogger[F]

      def pullGlobalSnapshots: F[List[Signed[GlobalSnapshot]]] = {
        for {
          lastStoredOrdinal <- lastOrdinalStorage.get
          nextOrdinal = lastStoredOrdinal.next
          l0Peer <- l0ClusterStorage.getRandomPeer

          pulled <- l0GlobalSnapshotClient.getLatestOrdinal
            .run(l0Peer)
            .map(nextOrdinal.value.value to _.value.value)
            .map(_.toList.map(o => SnapshotOrdinal(NonNegLong.unsafeFrom(o))))
            .flatMap { ordinals =>
              (ordinals, List.empty[Signed[GlobalSnapshot]]).tailRecM {
                case (ordinal :: nextOrdinals, snapshots) =>
                  l0GlobalSnapshotClient
                    .get(ordinal)(l0Peer)
                    .map(s => (nextOrdinals, snapshots :+ s).asLeft[List[Signed[GlobalSnapshot]]])
                    .handleErrorWith { e =>
                      logger
                        .warn(e)(s"Failure pulling snapshot with ordinal=$ordinal")
                        .map(_ => snapshots.asRight[(List[SnapshotOrdinal], List[Signed[GlobalSnapshot]])])
                    }

                case (Nil, snapshots) =>
                  Applicative[F].pure(snapshots.asRight[(List[SnapshotOrdinal], List[Signed[GlobalSnapshot]])])
              }
            }
            .map(NonEmptyList.fromList)
            .flatMap {
              case Some(snapshots) =>
                lastOrdinalStorage
                  .trySet(lastStoredOrdinal, snapshots.last.ordinal)
                  .ifM(
                    Applicative[F].pure(snapshots.toList),
                    Applicative[F].pure(List.empty[Signed[GlobalSnapshot]])
                  )
              case None =>
                Applicative[F].pure(List.empty[Signed[GlobalSnapshot]])
            }
        } yield pulled
      }.handleErrorWith { e =>
        logger.warn(e)(s"Failure pulling global snapshots!") >>
          Applicative[F].pure(List.empty[Signed[GlobalSnapshot]])
      }
    }
}
