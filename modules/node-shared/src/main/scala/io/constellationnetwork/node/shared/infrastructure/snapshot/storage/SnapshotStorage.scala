package io.constellationnetwork.node.shared.infrastructure.snapshot.storage

import cats.Order._
import cats.effect.std.{Mutex, Queue, Supervisor}
import cats.effect.{Async, Ref}
import cats.syntax.all._
import cats.{Applicative, MonadThrow}

import scala.concurrent.duration._

import io.constellationnetwork.cutoff.{LogarithmicOrdinalCutoff, OrdinalCutoff}
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.ext.cats.syntax.partialPrevious._
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.node.shared.domain.collateral.LatestBalances
import io.constellationnetwork.node.shared.domain.snapshot.storage.{SnapshotStorage => SnapshotStorageAlgebra}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher, HasherSelector}

import com.github.blemale.scaffeine.{Cache, Scaffeine}
import eu.timepit.refined.types.numeric.NonNegLong
import fs2.Stream
import fs2.concurrent.SignallingRef
import io.chrisdavenport.mapref.MapRef
import io.circe.Encoder
import org.typelevel.log4cats.slf4j.Slf4jLogger

object SnapshotStorage {

  /** Scaffeine cache for negative ordinal lookups. Caches ordinals confirmed absent from filesystem to avoid repeated stat() calls from
    * community nodes requesting pruned snapshots. Short TTL (30s) ensures new snapshots become visible quickly.
    */
  private def mkNegativeOrdinalCache: Cache[SnapshotOrdinal, Boolean] =
    Scaffeine()
      .expireAfterWrite(30.seconds)
      .maximumSize(10000)
      .build[SnapshotOrdinal, Boolean]()

  private def makeResources[F[_]: Async, S <: Snapshot, C <: SnapshotInfo[_]]() = {
    def mkHeadRef = SignallingRef.of[F, Option[(Signed[S], Hasher[F], C)]](none)
    def mkOrdinalCache = MapRef.ofSingleImmutableMap[F, SnapshotOrdinal, Hash](Map.empty)
    def mkHashCache = MapRef.ofSingleImmutableMap[F, Hash, Signed[S]](Map.empty)
    def mkNotPersistedCache = Ref.of(Set.empty[SnapshotOrdinal])
    def mkOffloadQueue = Queue.unbounded[F, SnapshotOrdinal]
    def mkCutoffQueue = Queue.unbounded[F, SnapshotOrdinal]
    def mkPersistenceMutex = Mutex[F]

    def mkLogger = Slf4jLogger.create[F]

    (mkHeadRef, mkOrdinalCache, mkHashCache, mkNotPersistedCache, mkOffloadQueue, mkCutoffQueue, mkPersistenceMutex, mkLogger).mapN {
      (_, _, _, _, _, _, _, _)
    }
  }

  def make[F[_]: Async, S <: Snapshot: Encoder, C <: SnapshotInfo[_]](
    snapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, S],
    snapshotInfoLocalFileSystemStorage: SnapshotInfoLocalFileSystemStorage[F, _, C],
    inMemoryCapacity: NonNegLong,
    snapshotInfoCutoffOrdinal: SnapshotOrdinal,
    hasherSelector: HasherSelector[F],
    combinedSnapshotCheckpointFileSystemStorage: CombinedSnapshotCheckpointFileSystemStorage[F, S, C],
    protectedSnapshotInfoOrdinals: Set[SnapshotOrdinal] = Set.empty
  )(implicit supervisor: Supervisor[F]): F[SnapshotStorageAlgebra[F, S, C] with LatestBalances[F]] =
    makeResources[F, S, C]().flatMap {
      case (headRef, ordinalCache, hashCache, notPersistedCache, offloadQueue, cutoffQueue, persistenceMutex, _) =>
        make(
          headRef,
          ordinalCache,
          hashCache,
          notPersistedCache,
          offloadQueue,
          cutoffQueue,
          persistenceMutex,
          snapshotLocalFileSystemStorage,
          snapshotInfoLocalFileSystemStorage,
          inMemoryCapacity,
          snapshotInfoCutoffOrdinal,
          hasherSelector,
          combinedSnapshotCheckpointFileSystemStorage,
          protectedSnapshotInfoOrdinals
        )
    }

  def make[F[_]: Async, S <: Snapshot: Encoder, C <: SnapshotInfo[_]](
    headRef: SignallingRef[F, Option[(Signed[S], Hasher[F], C)]],
    ordinalCache: MapRef[F, SnapshotOrdinal, Option[Hash]],
    hashCache: MapRef[F, Hash, Option[Signed[S]]],
    notPersistedCache: Ref[F, Set[SnapshotOrdinal]],
    offloadQueue: Queue[F, SnapshotOrdinal],
    snapshotInfoCutoffQueue: Queue[F, SnapshotOrdinal],
    persistenceMutex: Mutex[F],
    snapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, S],
    snapshotInfoLocalFileSystemStorage: SnapshotInfoLocalFileSystemStorage[F, _, C],
    inMemoryCapacity: NonNegLong,
    snapshotInfoCutoffOrdinal: SnapshotOrdinal,
    hasherSelector: HasherSelector[F],
    combinedSnapshotCheckpointFileSystemStorage: CombinedSnapshotCheckpointFileSystemStorage[F, S, C],
    protectedSnapshotInfoOrdinals: Set[SnapshotOrdinal]
  )(implicit supervisor: Supervisor[F]): F[SnapshotStorageAlgebra[F, S, C] with LatestBalances[F]] = {

    def logger = Slf4jLogger.getLogger[F]

    def cutoffLogic: OrdinalCutoff = LogarithmicOrdinalCutoff.make

    def offloadProcess: Stream[F, Unit] =
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
                    persistenceMutex.lock.surround {
                      ordinalCache(ordinal).get.flatMap {
                        case Some(hash) =>
                          hashCache(hash).get.flatMap {
                            case Some(snapshot) =>
                              Applicative[F].whenA(shouldPersist) {
                                hasherSelector.withCurrent { implicit hasher =>
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
                    }

                  offload.handleErrorWith { e =>
                    logger.error(e)(s"Failed offloading global snapshot! Snapshot ordinal=${ordinal.show}")
                  }
              }
            }
        }
        .void

    def snapshotInfoCutoffProcess: Stream[F, Unit] =
      Stream
        .fromQueueUnterminated(snapshotInfoCutoffQueue)
        .evalMap { _ =>
          persistenceMutex.lock.surround {
            // Resolve retention from the current head, not the queued ordinal.
            // A recovery can leave older cutoff notifications in the queue;
            // replaying a stale future ordinal after rollback could otherwise
            // prune the newly installed lower anchor.
            headRef.get.flatMap {
              case Some((current, _, _)) =>
                snapshotInfoLocalFileSystemStorage.listStoredOrdinals.flatMap {
                  _.compile.toList.map { stored =>
                    // Certified replay authenticates the activation transition from the exact
                    // A-1 artifact/context pair. Keep that one configured trust-root preimage in
                    // addition to the ordinary logarithmic operational history.
                    val retained =
                      cutoffLogic.cutoff(snapshotInfoCutoffOrdinal, current.ordinal) ++ protectedSnapshotInfoOrdinals

                    stored.toSet.diff(retained).toList
                  }
                    .flatMap(_.traverse_(snapshotInfoLocalFileSystemStorage.delete))
                }
              case None => Applicative[F].unit
            }
          }
        }
        .void

    def enqueue(snapshot: Signed[S], snapshotInfo: C)(implicit hasher: Hasher[F]) =
      persistenceMutex.lock.surround {
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
            snapshotInfoLocalFileSystemStorage
              .write(snapshot.ordinal, snapshotInfo)
              .handleErrorWith { error =>
                logger.error(error)(s"Failed writing required snapshot info to disk! ordinal=${snapshot.ordinal}") >>
                  error.raiseError[F, Unit]
              }
              .flatMap { _ =>
                snapshotInfoCutoffQueue.offer(snapshot.ordinal) >>
                  snapshot.ordinal
                    .partialPreviousN(inMemoryCapacity)
                    .fold(Applicative[F].unit)(offloadQueue.offer) >>
                  combinedSnapshotCheckpointFileSystemStorage.tryWrite(snapshot.ordinal, snapshot, snapshotInfo, hash)
              }
        }
      }

    def snapshotExists(snapshot: Signed[S])(implicit hasher: Hasher[F]): F[Boolean] =
      snapshot.toHashed
        .flatMap(hashed =>
          List(snapshotLocalFileSystemStorage.read(hashed.hash), snapshotLocalFileSystemStorage.read(snapshot.value.ordinal))
            .traverse(_.flatMap(_.traverse(_.toHashed).map(_.fold(false)(_.hash === hashed.hash))))
        )
        .map(_.reduce(_ && _))

    supervisor.supervise(offloadProcess.merge(snapshotInfoCutoffProcess).compile.drain).map { _ =>
      new SnapshotStorageAlgebra[F, S, C] with LatestBalances[F] {
        def prepend(snapshot: Signed[S], state: C)(implicit hasher: Hasher[F]): F[Boolean] = {

          def offer = enqueue(snapshot, state).as(true)

          def isExactCurrentValue(current: Signed[S], currentHasher: Hasher[F]): F[Boolean] = {
            val incomingHash = hasher.hash(snapshot.value)
            val currentHash = currentHasher.hash(current.value)
            (currentHash, incomingHash).mapN(_ === _)
          }

          def loop(implicit hasher: Hasher[F]): F[Boolean] =
            headRef.access.flatMap {
              case (v, setter) =>
                v match {
                  case None =>
                    setter((snapshot, hasher, state).some).ifM(offer, loop)
                  case Some((current, currentHasher, currentState)) =>
                    isNextSnapshot(current, currentHasher, snapshot).flatMap { isNext =>
                      if (isNext) setter((snapshot, hasher, state).some).ifM(offer, loop)
                      else
                        isExactCurrentValue(current, currentHasher).flatMap {
                          case true => enqueue(current, currentState)(currentHasher).as(true)
                          case false =>
                            logger
                              .debug(s"Trying to prepend ${snapshot.ordinal.show} but the current snapshot is: ${current.ordinal.show}")
                              .as(false)
                        }
                    }
                }
            }

          loop
        }

        def head: F[Option[(Signed[S], C)]] = headRef.get.map(_.map { case (snapshot, _, info) => (snapshot, info) })
        def headSnapshot: F[Option[Signed[S]]] = headRef.get.map(_.map(_._1))

        private val negativeCache: Cache[SnapshotOrdinal, Boolean] = mkNegativeOrdinalCache

        /** Read-through with negative caching: if the ordinal is known-absent (cached negative), skip filesystem entirely. On filesystem
          * miss, cache the negative result.
          */
        private def readFromDiskWithNegativeCache(ordinal: SnapshotOrdinal): F[Option[Signed[S]]] =
          Async[F].delay(negativeCache.getIfPresent(ordinal)).flatMap {
            case Some(_) => none[Signed[S]].pure[F] // known absent
            case None =>
              snapshotLocalFileSystemStorage.read(ordinal).flatTap {
                case None    => Async[F].delay(negativeCache.put(ordinal, true))
                case Some(_) => Async[F].unit
              }
          }

        def get(ordinal: SnapshotOrdinal): F[Option[Signed[S]]] =
          ordinalCache(ordinal).get.flatMap {
            case Some(hash) => get(hash)
            case None       => readFromDiskWithNegativeCache(ordinal)
          }

        def getHashed(ordinal: SnapshotOrdinal)(implicit hasher: Hasher[F]): F[Option[Hashed[S]]] =
          ordinalCache(ordinal).get.flatMap {
            case Some(hash) => get(hash).flatMap(_.traverse(_.toHashed))
            case None       => readFromDiskWithNegativeCache(ordinal).flatMap(_.traverse(_.toHashed))
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

        def setHeadForRecovery(snapshot: Signed[S], state: C)(implicit hasher: Hasher[F]): F[Unit] =
          logger.info(s"[SnapshotStorage] Recovery: setting head to ordinal=${snapshot.ordinal.show}") >>
            enqueue(snapshot, state) >>
            headRef.set((snapshot, hasher, state).some).void

        private def replaceExactRecoveryHead(snapshot: Signed[S], state: C)(implicit hasher: Hasher[F]): F[Unit] =
          snapshot.toHashed.flatMap { hashed =>
            for {
              cachedOrdinals <- ordinalCache.keys
              cachedHashes <- hashCache.keys
              staleOrdinals = cachedOrdinals.filter(_ >= snapshot.ordinal)
              staleHashes <- cachedHashes.filterA { hash =>
                hashCache(hash).get.map(
                  _.exists(cached => cached.ordinal > snapshot.ordinal || (cached.ordinal === snapshot.ordinal && hash =!= hashed.hash))
                )
              }
              _ <- logger.info(s"[SnapshotStorage] Recovery: atomically replacing exact head ordinal=${snapshot.ordinal.show}")
              _ <-
                snapshotLocalFileSystemStorage.replaceForRecovery(snapshot) >>
                  snapshotInfoLocalFileSystemStorage.replaceForRecovery(snapshot.ordinal, state) >>
                  combinedSnapshotCheckpointFileSystemStorage.replaceForRecovery(
                    snapshot.ordinal,
                    snapshot,
                    state,
                    hashed.hash
                  ) >>
                  staleOrdinals.traverse_(ordinal => ordinalCache(ordinal).set(none)) >>
                  staleHashes.traverse_(hash => hashCache(hash).set(none)) >>
                  hashCache(hashed.hash).set(snapshot.some) >>
                  ordinalCache(snapshot.ordinal).set(hashed.hash.some) >>
                  notPersistedCache.update(_.filter(_ < snapshot.ordinal)) >>
                  Async[F].delay(negativeCache.invalidate(snapshot.ordinal)) >>
                  headRef.set((snapshot, hasher, state).some).void
            } yield ()
          }

        override def setHeadForRecoveryExact(snapshot: Signed[S], state: C)(implicit hasher: Hasher[F]): F[Unit] =
          persistenceMutex.lock.surround(replaceExactRecoveryHead(snapshot, state))

        override def replaceCanonicalSuffixForRecovery(snapshot: Signed[S], state: C, cleanupSuffix: F[Unit])(
          implicit hasher: Hasher[F],
          F0: cats.effect.MonadCancelThrow[F]
        ): F[Unit] =
          persistenceMutex.lock.surround(cleanupSuffix >> replaceExactRecoveryHead(snapshot, state))

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
