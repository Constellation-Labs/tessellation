package io.constellationnetwork.node.shared.infrastructure.snapshot.storage

import cats.Order._
import cats.effect.std.{Queue, Supervisor}
import cats.effect.{Async, Ref}
import cats.syntax.all._
import cats.{Applicative, MonadThrow}

import io.constellationnetwork.cutoff.{LogarithmicOrdinalCutoff, OrdinalCutoff}
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.ext.cats.syntax.partialPrevious._
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.node.shared.domain.collateral.LatestBalances
import io.constellationnetwork.node.shared.domain.snapshot.storage.SnapshotStorage
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, HasherSelector}

import eu.timepit.refined.types.numeric.NonNegLong
import fs2.Stream
import fs2.concurrent.SignallingRef
import io.chrisdavenport.mapref.MapRef
import io.circe.Encoder
import org.typelevel.log4cats.slf4j.Slf4jLogger

object SnapshotStorage {

  private def makeResources[F[_]: Async, S <: Snapshot, C <: SnapshotInfo[_]]() = {
    def mkHeadRef = SignallingRef.of[F, Option[(Signed[S], Hasher[F], C)]](none)
    def mkOrdinalCache = MapRef.ofSingleImmutableMap[F, SnapshotOrdinal, Hash](Map.empty)
    def mkHashCache = MapRef.ofSingleImmutableMap[F, Hash, Signed[S]](Map.empty)
    def mkNotPersistedCache = Ref.of(Set.empty[SnapshotOrdinal])
    def mkOffloadQueue = Queue.unbounded[F, SnapshotOrdinal]
    def mkCutoffQueue = Queue.unbounded[F, SnapshotOrdinal]

    def mkLogger = Slf4jLogger.create[F]

    (mkHeadRef, mkOrdinalCache, mkHashCache, mkNotPersistedCache, mkOffloadQueue, mkCutoffQueue, mkLogger).mapN {
      (_, _, _, _, _, _, _)
    }
  }

  def make[F[_]: Async, S <: Snapshot: Encoder, C <: SnapshotInfo[_]](
    snapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, S],
    snapshotInfoLocalFileSystemStorage: SnapshotInfoLocalFileSystemStorage[F, _, C],
    inMemoryCapacity: NonNegLong,
    snapshotInfoCutoffOrdinal: SnapshotOrdinal,
    hasherSelector: HasherSelector[F]
  )(implicit supervisor: Supervisor[F]): F[SnapshotStorage[F, S, C] with LatestBalances[F]] =
    makeResources[F, S, C]().flatMap {
      case (headRef, ordinalCache, hashCache, notPersistedCache, offloadQueue, cutoffQueue, _) =>
        make(
          headRef,
          ordinalCache,
          hashCache,
          notPersistedCache,
          offloadQueue,
          cutoffQueue,
          snapshotLocalFileSystemStorage,
          snapshotInfoLocalFileSystemStorage,
          inMemoryCapacity,
          snapshotInfoCutoffOrdinal,
          hasherSelector
        )
    }

  def make[F[_]: Async, S <: Snapshot: Encoder, C <: SnapshotInfo[_]](
    headRef: SignallingRef[F, Option[(Signed[S], Hasher[F], C)]],
    ordinalCache: MapRef[F, SnapshotOrdinal, Option[Hash]],
    hashCache: MapRef[F, Hash, Option[Signed[S]]],
    notPersistedCache: Ref[F, Set[SnapshotOrdinal]],
    offloadQueue: Queue[F, SnapshotOrdinal],
    snapshotInfoCutoffQueue: Queue[F, SnapshotOrdinal],
    snapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, S],
    snapshotInfoLocalFileSystemStorage: SnapshotInfoLocalFileSystemStorage[F, _, C],
    inMemoryCapacity: NonNegLong,
    snapshotInfoCutoffOrdinal: SnapshotOrdinal,
    hasherSelector: HasherSelector[F]
  )(implicit supervisor: Supervisor[F]): F[SnapshotStorage[F, S, C] with LatestBalances[F]] = {

    def logger = Slf4jLogger.getLogger[F]

    def cutoffLogic: OrdinalCutoff = LogarithmicOrdinalCutoff.make

    def offloadProcess =
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
                              hasherSelector.forOrdinal(snapshot.ordinal) { implicit hasher =>
                                snapshotLocalFileSystemStorage.write(snapshot)
                              } >>
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

    def snapshotInfoCutoffProcess: Stream[F, Unit] =
      Stream
        .fromQueueUnterminated(snapshotInfoCutoffQueue)
        .evalMap { ordinal =>
          val toKeep = cutoffLogic.cutoff(snapshotInfoCutoffOrdinal, ordinal)

          snapshotInfoLocalFileSystemStorage.listStoredOrdinals.flatMap {
            _.compile.toList
              .map(_.toSet.diff(toKeep).toList)
              .flatMap(_.traverse(snapshotInfoLocalFileSystemStorage.delete))
          }
        }
        .void

    def enqueue(snapshot: Signed[S], snapshotInfo: C)(implicit hasher: Hasher[F]) =
      snapshot.value.hash.flatMap { hash =>
        hashCache(hash).set(snapshot.some) >>
          ordinalCache(snapshot.ordinal).set(hash.some) >>
          snapshotLocalFileSystemStorage.write(snapshot).handleErrorWith { e =>
            snapshotExists(snapshot).ifM(
              logger.info(s"Snapshot is already saved on disk. hash=$hash ordinal=${snapshot.ordinal}"),
              logger.error(e)(s"Failed writing snapshot to disk! hash=$hash ordinal=${snapshot.ordinal}") >>
                notPersistedCache.update(current => current + snapshot.ordinal)
            )
          } >>
          snapshotInfoLocalFileSystemStorage.write(snapshot.ordinal, snapshotInfo) >>
          snapshotInfoCutoffQueue.offer(snapshot.ordinal) >>
          snapshot.ordinal
            .partialPreviousN(inMemoryCapacity)
            .fold(Applicative[F].unit)(offloadQueue.offer)
      }

    def snapshotExists(snapshot: Signed[S])(implicit hasher: Hasher[F]): F[Boolean] =
      snapshot.toHashed
        .flatMap(hashed =>
          List(snapshotLocalFileSystemStorage.read(hashed.hash), snapshotLocalFileSystemStorage.read(snapshot.value.ordinal))
            .traverse(_.flatMap(_.traverse(_.toHashed).map(_.fold(false)(_.hash === hashed.hash))))
        )
        .map(_.reduce(_ && _))

    supervisor.supervise(offloadProcess.merge(snapshotInfoCutoffProcess).compile.drain).map { _ =>
      new SnapshotStorage[F, S, C] with LatestBalances[F] {
        def prepend(snapshot: Signed[S], state: C)(implicit hasher: Hasher[F]): F[Boolean] = {

          def offer = enqueue(snapshot, state).as(true)

          def loop(implicit hasher: Hasher[F]): F[Boolean] =
            headRef.access.flatMap {
              case (v, setter) =>
                v match {
                  case None =>
                    setter((snapshot, hasher, state).some).ifM(offer, loop)
                  case Some((current, currentHasher, _)) =>
                    isNextSnapshot(current, currentHasher, snapshot).flatMap { isNext =>
                      if (isNext) setter((snapshot, hasher, state).some).ifM(offer, loop)
                      else
                        logger
                          .debug(s"Trying to prepend ${snapshot.ordinal.show} but the current snapshot is: ${current.ordinal.show}")
                          .as(false)
                    }
                }
            }

          loop
        }

        def head: F[Option[(Signed[S], C)]] = headRef.get.map(_.map { case (snapshot, _, info) => (snapshot, info) })
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

        def getHash(ordinal: SnapshotOrdinal)(implicit hasher: Hasher[F]): F[Option[Hash]] =
          get(ordinal).flatMap {
            _.traverse(_.toHashed.map(_.hash))
          }

        def getLatestBalances: F[Option[Map[Address, Balance]]] =
          headRef.get.map(_.map(_._3.balances))

        def getLatestBalancesStream: Stream[F, Map[Address, Balance]] =
          headRef.discrete
            .map(_.map(_._3))
            .flatMap(_.fold[Stream[F, C]](Stream.empty)(Stream(_)))
            .map(_.balances)

        private def isNextSnapshot(
          current: Signed[S],
          currentHasher: Hasher[F],
          snapshot: Signed[S]
        ): F[Boolean] =
          currentHasher.hash(current.value).map { hash =>
            hash === snapshot.value.lastSnapshotHash && current.value.ordinal.next === snapshot.value.ordinal
          }
      }
    }
  }

}
