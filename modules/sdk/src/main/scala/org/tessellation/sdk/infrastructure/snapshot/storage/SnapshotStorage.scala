package org.tessellation.sdk.infrastructure.snapshot.storage

import cats.Order._
import cats.effect.std.{Queue, Supervisor}
import cats.effect.{Async, Ref}
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.contravariantSemigroupal._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.show._
import cats.syntax.traverse._
import cats.{Applicative, MonadThrow}

import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.cats.syntax.partialPrevious._
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo}
import org.tessellation.sdk.domain.collateral.LatestBalances
import org.tessellation.sdk.domain.snapshot.storage.SnapshotStorage
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import eu.timepit.refined.types.numeric.NonNegLong
import fs2.Stream
import fs2.concurrent.SignallingRef
import io.chrisdavenport.mapref.MapRef
import org.typelevel.log4cats.slf4j.Slf4jLogger

object SnapshotStorage {

  private def makeResources[F[_]: Async, S <: Snapshot[_, _], C <: SnapshotInfo[_]]() = {
    def mkHeadRef = SignallingRef.of[F, Option[(Signed[S], C)]](none)
    def mkOrdinalCache = MapRef.ofSingleImmutableMap[F, SnapshotOrdinal, Hash](Map.empty)
    def mkHashCache = MapRef.ofSingleImmutableMap[F, Hash, Signed[S]](Map.empty)
    def mkNotPersistedCache = Ref.of(Set.empty[SnapshotOrdinal])
    def mkOffloadQueue = Queue.unbounded[F, SnapshotOrdinal]

    def mkLogger = Slf4jLogger.create[F]

    (mkHeadRef, mkOrdinalCache, mkHashCache, mkNotPersistedCache, mkOffloadQueue, mkLogger).mapN {
      (_, _, _, _, _, _)
    }
  }

  def make[F[_]: Async: KryoSerializer, S <: Snapshot[_, _], C <: SnapshotInfo[_]](
    snapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, S],
    inMemoryCapacity: NonNegLong
  )(implicit supervisor: Supervisor[F]): F[SnapshotStorage[F, S, C] with LatestBalances[F]] =
    makeResources[F, S, C]().flatMap {
      case (headRef, ordinalCache, hashCache, notPersistedCache, offloadQueue, _) =>
        make(headRef, ordinalCache, hashCache, notPersistedCache, offloadQueue, snapshotLocalFileSystemStorage, inMemoryCapacity)
    }

  def make[F[_]: Async: KryoSerializer, S <: Snapshot[_, _], C <: SnapshotInfo[_]](
    headRef: SignallingRef[F, Option[(Signed[S], C)]],
    ordinalCache: MapRef[F, SnapshotOrdinal, Option[Hash]],
    hashCache: MapRef[F, Hash, Option[Signed[S]]],
    notPersistedCache: Ref[F, Set[SnapshotOrdinal]],
    offloadQueue: Queue[F, SnapshotOrdinal],
    snapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, S],
    inMemoryCapacity: NonNegLong
  )(implicit supervisor: Supervisor[F]): F[SnapshotStorage[F, S, C] with LatestBalances[F]] = {

    def logger = Slf4jLogger.getLogger[F]

    def offloadProcess: F[Unit] =
      Stream
        .fromQueueUnterminated(offloadQueue)
        .evalMap { cutOffOrdinal =>
          ordinalCache.keys
            .map(_.filter(_ <= cutOffOrdinal))
            .flatMap { toOffload =>
              notPersistedCache.get.map { toPersist =>
                val allOrdinals = toOffload.toSet ++ toPersist

                allOrdinals.map(o => (o, toPersist.contains(o), toOffload.contains(o))).toList.sorted
              }
            }
            .flatMap {
              _.traverse {
                case (ordinal, shouldPersist, shouldOffload) =>
                  def offload: F[Unit] =
                    ordinalCache(ordinal).get.flatMap {
                      case Some(hash) =>
                        hashCache(hash).get.flatMap {
                          case Some(snapshot) =>
                            Applicative[F].whenA(shouldPersist) {
                              snapshotLocalFileSystemStorage.write(snapshot) >>
                                notPersistedCache.update(current => current - ordinal)
                            } >>
                              Applicative[F].whenA(shouldOffload) {
                                ordinalCache(ordinal).set(none) >>
                                  hashCache(hash).set(none)
                              }
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
                    logger.error(e)(s"Failed offloading global snapshot! Snapshot ordinal=${ordinal.show}")
                  }
              }
            }
        }
        .compile
        .drain

    def enqueue(snapshot: Signed[S]) =
      snapshot.value.hashF.flatMap { hash =>
        hashCache(hash).set(snapshot.some) >>
          ordinalCache(snapshot.ordinal).set(hash.some) >>
          snapshotLocalFileSystemStorage.write(snapshot).handleErrorWith { e =>
            snapshotExists(snapshot).ifM(
              logger.info(s"Snapshot is already saved on disk. hash=$hash ordinal=${snapshot.ordinal}"),
              logger.error(e)(s"Failed writing snapshot to disk! hash=$hash ordinal=${snapshot.ordinal}") >>
                notPersistedCache.update(current => current + snapshot.ordinal)
            )
          } >>
          snapshot.ordinal
            .partialPreviousN(inMemoryCapacity)
            .fold(Applicative[F].unit)(offloadQueue.offer)
      }

    def snapshotExists(snapshot: Signed[S]): F[Boolean] =
      snapshot.toHashed
        .flatMap(hashed =>
          List(snapshotLocalFileSystemStorage.read(hashed.hash), snapshotLocalFileSystemStorage.read(snapshot.value.ordinal))
            .traverse(_.flatMap(_.traverse(_.toHashed).map(_.fold(false)(_.hash === hashed.hash))))
        )
        .map(_.reduce(_ && _))

    supervisor.supervise(offloadProcess).map { _ =>
      new SnapshotStorage[F, S, C] with LatestBalances[F] {
        def prepend(snapshot: Signed[S], state: C): F[Boolean] =
          headRef.modify {
            case None =>
              ((snapshot, state).some, enqueue(snapshot).map(_ => true))
            case Some((current, currentState)) =>
              isNextSnapshot(current, snapshot) match {
                case Left(e) =>
                  ((current, currentState).some, e.raiseError[F, Boolean])
                case Right(isNext) if isNext =>
                  ((snapshot, state).some, enqueue(snapshot).map(_ => true))

                case _ =>
                  (
                    (current, currentState).some,
                    logger
                      .debug(s"Trying to prepend ${snapshot.ordinal.show} but the current snapshot is: ${current.ordinal.show}")
                      .as(false)
                  )
              }
          }.flatten

        def head: F[Option[(Signed[S], C)]] = headRef.get
        def headSnapshot: F[Option[Signed[S]]] = headRef.get.map(_.map(_._1))

        def get(ordinal: SnapshotOrdinal): F[Option[Signed[S]]] =
          ordinalCache(ordinal).get.flatMap {
            case Some(hash) => get(hash)
            case None       => snapshotLocalFileSystemStorage.read(ordinal)
          }

        def get(hash: Hash): F[Option[Signed[S]]] =
          hashCache(hash).get.flatMap {
            case Some(s) => s.some.pure[F]
            case None    => snapshotLocalFileSystemStorage.read(hash)
          }

        def getLatestBalances: F[Option[Map[Address, Balance]]] =
          headRef.get.map(_.map(_._2.balances))

        def getLatestBalancesStream: Stream[F, Map[Address, Balance]] =
          headRef.discrete
            .map(_.map(_._2))
            .flatMap(_.fold[Stream[F, C]](Stream.empty)(Stream(_)))
            .map(_.balances)

        private def isNextSnapshot(
          current: Signed[S],
          snapshot: Signed[S]
        ): Either[Throwable, Boolean] =
          current.value.hash.map { hash =>
            hash === snapshot.value.lastSnapshotHash && current.value.ordinal.next === snapshot.value.ordinal
          }
      }
    }
  }

}
