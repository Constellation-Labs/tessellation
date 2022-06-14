package org.tessellation.infrastructure.snapshot

import cats.effect.std.Queue
import cats.effect.{Async, Ref, Spawn}
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.contravariantSemigroupal._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.partialOrder.catsSyntaxPartialOrder
import cats.syntax.show._
import cats.syntax.traverse._
import cats.{Applicative, MonadThrow}

import org.tessellation.dag.snapshot.{GlobalSnapshot, SnapshotOrdinal}
import org.tessellation.domain.snapshot.GlobalSnapshotStorage
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.cats.syntax.partialPrevious._
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.sdk.domain.collateral.LatestBalances
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto.autoUnwrap
import eu.timepit.refined.types.numeric.NonNegLong
import fs2.Stream
import io.chrisdavenport.mapref.MapRef
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object GlobalSnapshotStorage {

  def make[F[_]: Async: KryoSerializer](
    globalSnapshotLocalFileSystemStorage: GlobalSnapshotLocalFileSystemStorage[F],
    inMemoryCapacity: NonNegLong
  ): F[GlobalSnapshotStorage[F] with LatestBalances[F]] = {
    def mkHeadRef = Ref.of[F, Option[Signed[GlobalSnapshot]]](none)
    def mkOrdinalCache = MapRef.ofSingleImmutableMap[F, SnapshotOrdinal, Hash](Map.empty)
    def mkHashCache = MapRef.ofSingleImmutableMap[F, Hash, Signed[GlobalSnapshot]](Map.empty)
    def mkOffloadQueue = Queue.unbounded[F, SnapshotOrdinal]

    def mkLogger = Slf4jLogger.create[F]

    mkLogger.flatMap { implicit logger =>
      (mkHeadRef, mkOrdinalCache, mkHashCache, mkOffloadQueue).mapN {
        make(_, _, _, _, globalSnapshotLocalFileSystemStorage, inMemoryCapacity)
      }.flatten
    }
  }

  def make[F[_]: Async: Logger: KryoSerializer](
    headRef: Ref[F, Option[Signed[GlobalSnapshot]]],
    ordinalCache: MapRef[F, SnapshotOrdinal, Option[Hash]],
    hashCache: MapRef[F, Hash, Option[Signed[GlobalSnapshot]]],
    offloadQueue: Queue[F, SnapshotOrdinal],
    globalSnapshotLocalFileSystemStorage: GlobalSnapshotLocalFileSystemStorage[F],
    inMemoryCapacity: NonNegLong
  ): F[GlobalSnapshotStorage[F] with LatestBalances[F]] = {

    def offloadProcess: F[Unit] =
      Stream
        .fromQueueUnterminated(offloadQueue)
        .evalMap { cutOffOrdinal =>
          ordinalCache.keys
            .map(_.filter(_ <= cutOffOrdinal).sorted)
            .flatMap {
              _.traverse { ordinal =>
                def offload: F[Unit] =
                  ordinalCache(ordinal).get.flatMap {
                    case Some(hash) =>
                      hashCache(hash).get.flatMap {
                        case Some(snapshot) =>
                          globalSnapshotLocalFileSystemStorage.write(snapshot) >>
                            ordinalCache(ordinal).set(none) >>
                            hashCache(hash).set(none)
                        case None =>
                          MonadThrow[F].raiseError[Unit](
                            new Throwable("Unexpected state: ordinal and hash found but snapshot not found")
                          )
                      }
                    case None =>
                      MonadThrow[F].raiseError[Unit](
                        new Throwable("Unexpected state: hash not found but ordinal exists")
                      )
                  }

                offload.handleErrorWith { e =>
                  Logger[F].error(e)(s"Failed offloading global snapshot to disk! Snapshot ordinal=${ordinal.show}")
                }
              }
            }
        }
        .compile
        .drain

    def enqueue(snapshot: Signed[GlobalSnapshot]) =
      snapshot.value.hashF.flatMap { hash =>
        hashCache(hash).set(snapshot.some) >> ordinalCache(snapshot.ordinal).set(hash.some) >>
          snapshot.ordinal
            .partialPreviousN(inMemoryCapacity)
            .fold(Applicative[F].unit)(offloadQueue.offer(_))
      }

    Spawn[F].start { offloadProcess }.map { _ =>
      new GlobalSnapshotStorage[F] with LatestBalances[F] {
        def prepend(snapshot: Signed[GlobalSnapshot]): F[Boolean] =
          headRef.modify {
            case None =>
              (snapshot.some, enqueue(snapshot).map(_ => true))
            case Some(current) =>
              isNextSnapshot(current, snapshot) match {
                case Left(e) =>
                  (current.some, e.raiseError[F, Boolean])
                case Right(isNext) if isNext =>
                  (snapshot.some, enqueue(snapshot).map(_ => true))

                case _ => (current.some, false.pure[F])
              }
          }.flatten

        def head: F[Option[Signed[GlobalSnapshotArtifact]]] = headRef.get

        def get(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalSnapshotArtifact]]] =
          ordinalCache(ordinal).get.flatMap {
            case Some(hash) => get(hash)
            case None       => globalSnapshotLocalFileSystemStorage.read(ordinal)
          }

        def get(hash: Hash): F[Option[Signed[GlobalSnapshotArtifact]]] =
          hashCache(hash).get.flatMap {
            case Some(s) => s.some.pure[F]
            case None    => globalSnapshotLocalFileSystemStorage.read(hash)
          }

        def getLatestBalances: F[Option[Map[Address, Balance]]] =
          head.map(_.map(_.info.balances))

        private def isNextSnapshot(
          current: Signed[GlobalSnapshot],
          snapshot: Signed[GlobalSnapshot]
        ): Either[Throwable, Boolean] =
          current.value.hash.map { hash =>
            hash === snapshot.value.lastSnapshotHash && current.value.ordinal.next === snapshot.value.ordinal
          }
      }
    }
  }

}
