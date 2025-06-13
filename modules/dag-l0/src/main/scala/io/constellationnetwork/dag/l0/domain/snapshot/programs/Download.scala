package io.constellationnetwork.dag.l0.domain.snapshot.programs

import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.all._
import cats.{Applicative, Parallel}

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

import io.constellationnetwork.dag.l0.domain.snapshot.storages.SnapshotDownloadStorage
import io.constellationnetwork.dag.l0.http.p2p.P2PClient
import io.constellationnetwork.dag.l0.infrastructure.snapshot.{GlobalSnapshotConsensus, GlobalSnapshotContext}
import io.constellationnetwork.ext.cats.kernel.PartialPrevious
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.merkletree.StateProofValidator
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.programs.Download
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastNGlobalSnapshotStorage
import io.constellationnetwork.node.shared.domain.snapshot.{PeerSelect, Validator}
import io.constellationnetwork.node.shared.infrastructure.snapshot.GlobalSnapshotContextFunctions
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.Peer
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.RetryPolicies._
import retry._

object Download {
  def make[F[_]: Async: Parallel: Random: KryoSerializer](
    snapshotStorage: SnapshotDownloadStorage[F],
    p2pClient: P2PClient[F],
    clusterStorage: ClusterStorage[F],
    lastFullGlobalSnapshotOrdinal: SnapshotOrdinal,
    globalSnapshotContextFns: GlobalSnapshotContextFunctions[F],
    nodeStorage: NodeStorage[F],
    consensus: GlobalSnapshotConsensus[F],
    peerSelect: PeerSelect[F],
    lastNGlobalSnapshotStorage: LastNGlobalSnapshotStorage[F]
  ): Download[F] = new Download[F] {

    val logger = Slf4jLogger.getLogger[F]

    val minBatchSizeToStartObserving: Long = 1L
    val observationOffset = NonNegLong(4L)
    val fetchSnapshotDelayBetweenTrials = 10.seconds

    type DownloadResult = (Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)
    type ObservationLimit = SnapshotOrdinal

    private def fetchSnapshotByOrdinal(implicit hasherSelector: HasherSelector[F]) = (ordinal: SnapshotOrdinal) =>
      hasherSelector.withCurrent { implicit hasher =>
        fetchSnapshot(none, ordinal).flatMap(_.toHashed.map(_.some))
      }

    def download(implicit hasherSelector: HasherSelector[F]): F[Unit] =
      nodeStorage
        .tryModifyState(NodeState.WaitingForDownload, NodeState.DownloadInProgress, NodeState.WaitingForObserving)(start)
        .flatMap(observe)
        .flatMap { result =>
          val ((snapshot, context), observationLimit) = result
          hasherSelector.withCurrent { implicit hasher =>
            for {
              hashedSnapshot <- snapshot.toHashed
              _ <- lastNGlobalSnapshotStorage.setInitialFetchingGL0(hashedSnapshot, context, fetchSnapshot)
              _ <- consensus.manager.startFacilitatingAfterDownload(observationLimit, snapshot, context)
            } yield ()
          }
        }
        .onError(logger.error(_)("Unexpected failure during download!"))

    def start(implicit hasherSelector: HasherSelector[F]): F[DownloadResult] = {
      def latestMetadata = peerSelect.select.flatMap {
        p2pClient.globalSnapshot.getLatestMetadata.run(_)
      }

      def go(startingPoint: SnapshotOrdinal, result: Option[DownloadResult]): F[DownloadResult] =
        latestMetadata.flatTap { metadata =>
          Async[F].whenA(result.isEmpty)(
            logger.info(s"[Download] Cleanup for snapshots greater than ${metadata.ordinal}") >>
              snapshotStorage.cleanupAbove(metadata.ordinal)
          )
        }.flatTap { metadata =>
          logger.info(s"Download for startingPoint=${startingPoint}. Latest metadata=${metadata.show}")
        }.flatMap { metadata =>
          val batchSize = metadata.ordinal.value.value - startingPoint.value.value

          if (batchSize <= minBatchSizeToStartObserving && startingPoint =!= lastFullGlobalSnapshotOrdinal) {
            result.map(_.pure[F]).getOrElse(UnexpectedState.raiseError[F, DownloadResult])
          } else
            download(metadata.hash, metadata.ordinal, result).flatMap {
              case (snapshot, context) => go(snapshot.ordinal, (snapshot, context).some)
            }
        }

      go(lastFullGlobalSnapshotOrdinal, none[DownloadResult])
    }

    def observe(result: DownloadResult)(implicit hasherSelector: HasherSelector[F]): F[(DownloadResult, ObservationLimit)] = {
      val (lastSnapshot, _) = result

      val observationLimit = SnapshotOrdinal(lastSnapshot.ordinal.value |+| observationOffset)

      def go(result: DownloadResult): F[DownloadResult] = {
        val (lastSnapshot, _) = result

        if (lastSnapshot.ordinal === observationLimit) {
          result.pure[F]
        } else fetchNextSnapshot(result) >>= go
      }

      consensus.manager.registerForConsensus(observationLimit) >>
        go(result).map((_, observationLimit))
    }

    def fetchNextSnapshot(result: DownloadResult)(implicit hasherSelector: HasherSelector[F]): F[DownloadResult] = {
      def retryPolicy = constantDelay(fetchSnapshotDelayBetweenTrials)

      def isWorthRetrying(err: Throwable): F[Boolean] = err match {
        case CannotFetchSnapshot | InvalidChain => true.pure[F]
        case _                                  => false.pure[F]
      }

      retryingOnSomeErrors(retryPolicy, isWorthRetrying, retry.noop[F, Throwable]) {
        val (lastSnapshot, lastContext) = result

        fetchSnapshot(none, lastSnapshot.ordinal.next).flatMap { snapshot =>
          hasherSelector
            .forOrdinal(lastSnapshot.ordinal) { implicit hasher =>
              lastSnapshot.toHashed[F]
            }
            .flatMap { hashed =>
              Applicative[F].unlessA {
                Validator.isNextSnapshot(hashed, snapshot.value)
              }(InvalidChain.raiseError[F, Unit])
            } >>
            HasherSelector[F]
              .forOrdinal(snapshot.ordinal) { implicit hasher =>
                globalSnapshotContextFns
                  .createContext(lastContext, lastSnapshot, snapshot, lastNGlobalSnapshotStorage.getLastN, fetchSnapshotByOrdinal)
              }
              .handleErrorWith(_ => InvalidChain.raiseError[F, GlobalSnapshotContext])
              .flatTap { _ =>
                snapshotStorage.writePersisted(snapshot)
              }
              .map((snapshot, _))

        }
      }
    }

    def download(hash: Hash, ordinal: SnapshotOrdinal, state: Option[DownloadResult])(
      implicit hasherSelector: HasherSelector[F]
    ): F[DownloadResult] = {

      def go(tmpMap: Map[SnapshotOrdinal, Hash], stepHash: Hash, stepOrdinal: SnapshotOrdinal): F[DownloadResult] =
        isSnapshotPersistedOrReachedGenesis(stepHash, stepOrdinal).ifM(
          snapshotStorage.getHighestSnapshotInfoOrdinal(lte = stepOrdinal).flatMap {
            validateChain(tmpMap, _, ordinal, state)
          },
          snapshotStorage
            .readTmp(stepOrdinal)
            .flatMap {
              case Some(snapshot) =>
                hasherSelector.forOrdinal(snapshot.ordinal)(implicit hasher => snapshot.toHashed[F]).map { hashed =>
                  if (hashed.hash === stepHash) hashed.some else none[Hashed[GlobalIncrementalSnapshot]]
                }
              case None => none[Hashed[GlobalIncrementalSnapshot]].pure[F]
            }
            .flatMap {
              _.map(_.pure[F])
                .getOrElse(fetchSnapshot(stepHash.some, stepOrdinal).flatMap { snapshot =>
                  hasherSelector.forOrdinal(snapshot.ordinal) { implicit hasher =>
                    snapshotStorage.writeTmp(snapshot).flatMap(_ => snapshot.toHashed[F])
                  }
                })
                .flatMap { hashed =>
                  def updated = tmpMap + (hashed.ordinal -> hashed.hash)

                  PartialPrevious[SnapshotOrdinal]
                    .partialPrevious(hashed.ordinal)
                    .map {
                      go(updated, hashed.lastSnapshotHash, _)
                    }
                    .getOrElse(HashAndOrdinalMismatch.raiseError[F, DownloadResult])
                }
            }
        )

      go(Map.empty, hash, ordinal)
    }

    def isSnapshotPersistedOrReachedGenesis(hash: Hash, ordinal: SnapshotOrdinal): F[Boolean] = {
      def isSnapshotPersisted = snapshotStorage.isPersisted(hash)

      def didReachGenesis = ordinal === lastFullGlobalSnapshotOrdinal

      if (!didReachGenesis) {
        isSnapshotPersisted
      } else true.pure[F]
    }

    def validateChain(
      tmpMap: Map[SnapshotOrdinal, Hash],
      startingOrdinal: Option[SnapshotOrdinal],
      endingOrdinal: SnapshotOrdinal,
      state: Option[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]
    )(implicit hasherSelector: HasherSelector[F]): F[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)] = {

      type Agg = DownloadResult

      def go(lastSnapshot: Signed[GlobalIncrementalSnapshot], context: GlobalSnapshotInfo): F[Agg] = {
        val nextOrdinal = lastSnapshot.ordinal.next

        def readSnapshot: F[Option[Signed[GlobalIncrementalSnapshot]]] = tmpMap
          .get(nextOrdinal)
          .as(snapshotStorage.readTmp(nextOrdinal))
          .getOrElse(snapshotStorage.readPersisted(nextOrdinal))

        def persistLastSnapshot: F[Unit] =
          Applicative[F].whenA(tmpMap.contains(lastSnapshot.ordinal)) {
            snapshotStorage.readPersisted(lastSnapshot.ordinal).flatMap {
              _.map(snapshot =>
                hasherSelector
                  .forOrdinal(lastSnapshot.ordinal)(implicit hasher => snapshot.toHashed[F])
                  .map(_.hash)
                  .flatMap(snapshotStorage.movePersistedToTmp(_, lastSnapshot.ordinal))
                  .handleErrorWith { error =>
                    implicit val kryoHasher = Hasher.forKryo[F]
                    logger.warn(error)(s"movePersistedToTmp failed for ordinal=${lastSnapshot.ordinal}, retrying with Kryo hasher") >>
                      snapshot.toHashed[F].map(_.hash).flatMap(snapshotStorage.movePersistedToTmp(_, lastSnapshot.ordinal))

                  }
              ).getOrElse(Applicative[F].unit)
            } >>
              snapshotStorage
                .moveTmpToPersisted(lastSnapshot)
          }

        def processNextOrFinish: F[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)] =
          if (lastSnapshot.ordinal.value >= endingOrdinal.value) {
            (lastSnapshot, context).pure[F]
          } else
            readSnapshot.flatMap {
              case Some(snapshot) =>
                HasherSelector[F]
                  .forOrdinal(snapshot.ordinal) { implicit hasher =>
                    globalSnapshotContextFns
                      .createContext(context, lastSnapshot, snapshot, lastNGlobalSnapshotStorage.getLastN, fetchSnapshotByOrdinal)
                  }
                  .flatTap(newContext =>
                    hasherSelector
                      .forOrdinal(snapshot.ordinal) { implicit hasher =>
                        snapshotStorage
                          .hasCorrectSnapshotInfo(snapshot.ordinal, snapshot.stateProof)
                          .ifM(
                            ().pure[F],
                            (Hasher[F].getLogic(snapshot.ordinal) match {
                              case JsonHash => StateProofValidator.validate(snapshot, newContext).map(_.isValid)
                              case KryoHash =>
                                StateProofValidator
                                  .validate(snapshot, GlobalSnapshotInfoV2.fromGlobalSnapshotInfo(newContext))
                                  .map(_.isValid)
                            })
                              .ifM(
                                snapshotStorage.persistSnapshotInfoWithCutoff(snapshot.ordinal, newContext),
                                InvalidStateProof(snapshot.ordinal).raiseError[F, Unit]
                              )
                          )
                      }
                  )
                  .flatMap(go(snapshot, _))
              case None => InvalidChain.raiseError[F, Agg]
            }

        persistLastSnapshot >>
          processNextOrFinish
      }

      state
        .map(_.pure[F])
        .getOrElse {
          startingOrdinal
            .flatTraverse(ordinal => hasherSelector.forOrdinal(ordinal)(implicit hasher => snapshotStorage.readCombined(ordinal)))
            .flatMap {
              _.map(_.pure[F]).getOrElse(
                getGenesisSnapshot(tmpMap)
              )
            }
        }
        .flatMap { case (s, c) => go(s, c) }
    }

    def getGenesisSnapshot(
      tmpMap: Map[SnapshotOrdinal, Hash]
    )(implicit hasherSelector: HasherSelector[F]): F[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)] =
      snapshotStorage
        .readGenesis(lastFullGlobalSnapshotOrdinal)
        .flatMap {
          _.map(_.pure[F]).getOrElse {
            hasherSelector.forOrdinal(lastFullGlobalSnapshotOrdinal) { implicit hasher =>
              fetchGenesis(lastFullGlobalSnapshotOrdinal)
                .flatTap(snapshotStorage.writeGenesis)
            }
          }
        }
        .flatMap { genesis =>
          val incrementalGenesisOrdinal = genesis.ordinal.next

          tmpMap
            .get(incrementalGenesisOrdinal)
            .as(snapshotStorage.readTmp(incrementalGenesisOrdinal))
            .getOrElse(snapshotStorage.readPersisted(incrementalGenesisOrdinal))
            .flatMap {
              case Some(snapshot) => (genesis.value, snapshot).pure[F]
              case None           => FirstIncrementalNotFound.raiseError[F, (GlobalSnapshot, Signed[GlobalIncrementalSnapshot])]
            }
            .map { case (full, incremental) => (incremental, GlobalSnapshotInfoV1.toGlobalSnapshotInfo(full.info)) }
        }

    def fetchSnapshot(hash: Option[Hash], ordinal: SnapshotOrdinal)(
      implicit hasherSelector: HasherSelector[F]
    ): F[Signed[GlobalIncrementalSnapshot]] =
      clusterStorage.getResponsivePeers
        .map(NodeState.ready)
        .map { peers =>
          if (sys.env.get("CL_EXIT_ON_FORK").contains("true")) {
            clusterStorage.prioritySeedlist match {
              case Some(seeds) =>
                val ids = seeds.map(x => x.peerId)
                val hasPriorityPeer = peers.exists(p => ids.contains(p.id))
                if (!hasPriorityPeer) {
                  println("Exit on fork due to missing priority peer")
                  System.exit(1)
                }
              case _ =>
            }
          }
          peers
        }
        .map(_.toList)
        .flatMap(Random[F].shuffleList)
        .flatTap { _ =>
          logger.info(s"Downloading snapshot hash=${hash.show}, ordinal=${ordinal.show}")
        }
        .flatMap { peers =>
          type Success = Signed[GlobalIncrementalSnapshot]
          type Result = Option[Success]
          type Agg = (List[Peer], Result)

          (peers, none[Success]).tailRecM[F, Result] {
            case (Nil, snapshot) => snapshot.asRight[Agg].pure[F]
            case (peer :: tail, _) =>
              p2pClient.globalSnapshot
                .get(ordinal)
                .run(peer)
                .flatMap(snapshot => hasherSelector.forOrdinal(snapshot.ordinal)(implicit hasher => snapshot.toHashed[F]))
                .map(_.some)
                .handleErrorWith(e =>
                  logger
                    .warn(e)(s"Unable to retrieve snapshot at ordinal ${ordinal.show} from peer ${peer.show}")
                    .as(none[Hashed[GlobalIncrementalSnapshot]])
                )
                .map {
                  case Some(snapshot) if hash.forall(_ === snapshot.hash) => snapshot.signed.some.asRight[Agg]
                  case _                                                  => (tail, none[Success]).asLeft[Result]
                }
          }
        }
        .flatMap {
          case Some(snapshot) => snapshot.pure[F]
          case _              => CannotFetchSnapshot.raiseError[F, Signed[GlobalIncrementalSnapshot]]
        }

    def fetchGenesis(ordinal: SnapshotOrdinal)(implicit hasher: Hasher[F]): F[Signed[GlobalSnapshot]] =
      clusterStorage.getResponsivePeers
        .map(NodeState.ready)
        .map(_.toList)
        .flatMap(Random[F].shuffleList)
        .flatTap { _ =>
          logger.info(s"Downloading genesis snapshot ordinal=${ordinal}")
        }
        .flatMap { peers =>
          type Success = Signed[GlobalSnapshot]
          type Agg = (List[Peer], Option[Signed[GlobalSnapshot]])
          type Result = Option[Success]

          (peers, none[Success]).tailRecM[F, Result] {
            case (Nil, snapshot) => snapshot.asRight[Agg].pure[F]
            case (peer :: tail, _) =>
              p2pClient.globalSnapshot
                .getFull(ordinal)
                .run(peer)
                .flatMap(_.toHashed[F])
                .map(_.some)
                .handleError(_ => none[Hashed[GlobalSnapshot]])
                .map {
                  case Some(snapshot) => snapshot.signed.some.asRight[Agg]
                  case _              => (tail, none[Success]).asLeft[Result]
                }
          }
        }
        .flatMap {
          case Some(snapshot) => snapshot.pure[F]
          case _              => CannotFetchGenesisSnapshot.raiseError[F, Signed[GlobalSnapshot]]
        }
  }

  case object HashAndOrdinalMismatch extends NoStackTrace

  case object CannotFetchSnapshot extends NoStackTrace

  case object CannotFetchGenesisSnapshot extends NoStackTrace

  case object FirstIncrementalNotFound extends NoStackTrace

  case object InvalidChain extends NoStackTrace

  case class InvalidStateProof(ordinal: SnapshotOrdinal) extends NoStackTrace

  case object UnexpectedState extends NoStackTrace
}
