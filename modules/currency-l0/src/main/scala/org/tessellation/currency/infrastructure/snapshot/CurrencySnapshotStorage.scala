package org.tessellation.currency.infrastructure.snapshot

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

import org.tessellation.currency.schema.currency.CurrencySnapshot
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.cats.syntax.partialPrevious._
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.sdk.domain.collateral.LatestBalances
import org.tessellation.sdk.domain.snapshot.storage.SnapshotStorage
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import fs2.Stream
import fs2.concurrent.SignallingRef
import io.chrisdavenport.mapref.MapRef
import org.typelevel.log4cats.slf4j.Slf4jLogger

object CurrencySnapshotStorage {

  private def makeResources[F[_]: Async]() = {
    def mkHeadRef = SignallingRef.of[F, Option[Signed[CurrencySnapshot]]](none)
    def mkOrdinalCache = MapRef.ofSingleImmutableMap[F, SnapshotOrdinal, Hash](Map.empty)
    def mkHashCache = MapRef.ofSingleImmutableMap[F, Hash, Signed[CurrencySnapshot]](Map.empty)
    def mkNotPersistedCache = Ref.of(Set.empty[SnapshotOrdinal])
    def mkOffloadQueue = Queue.unbounded[F, SnapshotOrdinal]

    def mkLogger = Slf4jLogger.create[F]

    (mkHeadRef, mkOrdinalCache, mkHashCache, mkNotPersistedCache, mkOffloadQueue, mkLogger).mapN {
      (_, _, _, _, _, _)
    }
  }

  def make[F[_]: Async: KryoSerializer](
    snapshotLocalFileSystemStorage: CurrencySnapshotLocalFileSystemStorage[F],
    inMemoryCapacity: NonNegLong
  )(implicit S: Supervisor[F]): F[SnapshotStorage[F, CurrencySnapshot] with LatestBalances[F]] =
    makeResources().flatMap {
      case (headRef, ordinalCache, hashCache, notPersistedCache, offloadQueue, _) =>
        make(headRef, ordinalCache, hashCache, notPersistedCache, offloadQueue, snapshotLocalFileSystemStorage, inMemoryCapacity)
    }

  def make[F[_]: Async: KryoSerializer](
    headRef: SignallingRef[F, Option[Signed[CurrencySnapshot]]],
    ordinalCache: MapRef[F, SnapshotOrdinal, Option[Hash]],
    hashCache: MapRef[F, Hash, Option[Signed[CurrencySnapshot]]],
    notPersistedCache: Ref[F, Set[SnapshotOrdinal]],
    offloadQueue: Queue[F, SnapshotOrdinal],
    snapshotLocalFileSystemStorage: CurrencySnapshotLocalFileSystemStorage[F],
    inMemoryCapacity: NonNegLong
  )(implicit S: Supervisor[F]): F[SnapshotStorage[F, CurrencySnapshot] with LatestBalances[F]] = {

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

    def enqueue(snapshot: Signed[CurrencySnapshot]) =
      snapshot.value.hashF.flatMap { hash =>
        hashCache(hash).set(snapshot.some) >>
          ordinalCache(snapshot.ordinal).set(hash.some) >>
          snapshotLocalFileSystemStorage.write(snapshot).handleErrorWith { e =>
            logger.error(e)(s"Failed writing snapshot to disk! hash=$hash ordinal=${snapshot.ordinal}") >>
              notPersistedCache.update(current => current + snapshot.ordinal)
          } >>
          snapshot.ordinal
            .partialPreviousN(inMemoryCapacity)
            .fold(Applicative[F].unit)(offloadQueue.offer)
      }

    S.supervise(offloadProcess).map { _ =>
      new SnapshotStorage[F, CurrencySnapshot] with LatestBalances[F] {
        def prepend(snapshot: Signed[CurrencySnapshot]): F[Boolean] =
          headRef.modify {
            case None =>
              (snapshot.some, enqueue(snapshot).map(_ => true))
            case Some(current) =>
              isNextSnapshot(current, snapshot) match {
                case Left(e) =>
                  (current.some, e.raiseError[F, Boolean])
                case Right(isNext) if isNext =>
                  (snapshot.some, enqueue(snapshot).map(_ => true))

                case _ =>
                  (
                    current.some,
                    logger
                      .debug(s"Trying to prepend ${snapshot.ordinal.show} but the current snapshot is: ${current.ordinal.show}")
                      .as(false)
                  )
              }
          }.flatten

        def head: F[Option[Signed[CurrencySnapshotArtifact]]] = headRef.get

        def get(ordinal: SnapshotOrdinal): F[Option[Signed[CurrencySnapshotArtifact]]] =
          ordinalCache(ordinal).get.flatMap {
            case Some(hash) => get(hash)
            case None       => snapshotLocalFileSystemStorage.read(ordinal)
          }

        def get(hash: Hash): F[Option[Signed[CurrencySnapshotArtifact]]] =
          hashCache(hash).get.flatMap {
            case Some(s) => s.some.pure[F]
            case None    => snapshotLocalFileSystemStorage.read(hash)
          }

        def getLatestBalances: F[Option[Map[Address, Balance]]] =
          head.map(_.map(_.info.balances))

        def getLatestBalancesStream: Stream[F, Map[Address, Balance]] =
          headRef.discrete
            .flatMap(_.fold[Stream[F, Signed[CurrencySnapshotArtifact]]](Stream.empty)(Stream(_)))
            .map(_.info.balances)

        private def isNextSnapshot(
          current: Signed[CurrencySnapshot],
          snapshot: Signed[CurrencySnapshot]
        ): Either[Throwable, Boolean] =
          current.value.hash.map { hash =>
            hash === snapshot.value.lastSnapshotHash && current.value.ordinal.next === snapshot.value.ordinal
          }
      }
    }
  }

}
