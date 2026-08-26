package io.constellationnetwork.currency.l0.snapshot.programs

import cats.Applicative
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.all._

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

import io.constellationnetwork.currency.dataApplication.{BaseDataApplicationL0Service, DataCalculatedState, L0NodeContext}
import io.constellationnetwork.currency.l0.domain.snapshot.storages.CurrencySnapshotCleanupStorage
import io.constellationnetwork.currency.l0.http.p2p.P2PClient
import io.constellationnetwork.currency.l0.snapshot.CurrencySnapshotConsensus
import io.constellationnetwork.currency.l0.snapshot.services.StateChannelBinarySender
import io.constellationnetwork.currency.l0.snapshot.storage.{RecoverySyncPublicationStorage, StateChannelBinaryOutboxStorage}
import io.constellationnetwork.currency.schema.CurrencyStateKey
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.collateral.LatestBalances
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.programs.Download
import io.constellationnetwork.node.shared.domain.snapshot.storage.{ExactSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.node.shared.domain.snapshot.{PeerSelect, Validator}
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.node.shared.infrastructure.snapshot.CurrencySnapshotContextFunctions
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.{CombinedSnapshotCheckpointFileSystemStorage, IdentifierStorage}
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotEvent
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.L0Peer.toP2PContext
import io.constellationnetwork.schema.peer.{L0Peer, Peer}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher, HasherSelector}

import eu.timepit.refined.types.numeric.NonNegLong
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.RetryPolicies._
import retry._

/** Stable release/mainnet observe-and-register download flow with current crash-safe storage replacement. */
object Download {

  /** A single successor gets roughly one normal Currency round to become available. Exhaustion returns control to DownloadDaemon, which
    * reselects a fresh latest anchor. Every successfully downloaded successor starts with a fresh budget.
    */
  private[snapshot] val fetchNextRetryCap: Int = 6

  private[snapshot] def shouldReanchorAfterFailure(state: NodeState): Boolean =
    Set[NodeState](
      NodeState.DownloadInProgress,
      NodeState.WaitingForObserving,
      NodeState.Observing,
      NodeState.WaitingForReady
    ).contains(state)

  private[snapshot] def observationLimit(current: SnapshotOrdinal, offset: Long): SnapshotOrdinal =
    SnapshotOrdinal.unsafeApply(Math.addExact(current.value.value, offset))

  def make[F[_]: Async: Random](
    p2pClient: P2PClient[F],
    clusterStorage: ClusterStorage[F],
    currencySnapshotContextFns: CurrencySnapshotContextFunctions[F],
    nodeStorage: NodeStorage[F],
    consensus: CurrencySnapshotConsensus[F],
    peerSelect: PeerSelect[F],
    identifierStorage: IdentifierStorage[F],
    maybeDataApplication: Option[BaseDataApplicationL0Service[F]],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    snapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo] with LatestBalances[F],
    currencySnapshotCleanupStorage: CurrencySnapshotCleanupStorage[F],
    combinedSnapshotCheckpointFileSystemStorage: CombinedSnapshotCheckpointFileSystemStorage[
      F,
      CurrencyIncrementalSnapshot,
      CurrencySnapshotInfo
    ],
    eventMempool: EventMempool[F, CurrencySnapshotEvent, CurrencyStateKey],
    stateChannelBinarySender: StateChannelBinarySender[F],
    recoverySyncPublicationStorage: RecoverySyncPublicationStorage[F],
    stateChannelBinaryOutboxStorage: StateChannelBinaryOutboxStorage[F]
  )(implicit l0NodeContext: L0NodeContext[F]): Download[F, CurrencyIncrementalSnapshot] = new Download[F, CurrencyIncrementalSnapshot] {

    private val logger = Slf4jLogger.getLogger[F]
    private val observationOffset = NonNegLong(4L)
    private val fetchSnapshotDelayBetweenTrials = 10.seconds

    type DownloadResult = (Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)
    type ObservationLimit = SnapshotOrdinal

    def recoveryDownload(implicit hasherSelector: HasherSelector[F]): F[Unit] = download

    def download(implicit hasherSelector: HasherSelector[F]): F[Unit] = {
      implicit val hasher: Hasher[F] = hasherSelector.getCurrent

      val run = stateChannelBinarySender.disablePublishing >>
        nodeStorage
          .tryModifyState(NodeState.WaitingForDownload, NodeState.DownloadInProgress, NodeState.WaitingForObserving)(start)
          .flatMap(observe)
          .flatMap {
            case ((snapshot, context), observationLimit) =>
              for {
                currencyAddress <- identifierStorage.get
                _ <- consensus.manager.startFacilitatingAfterDownload(
                  observationLimit,
                  snapshot,
                  CurrencySnapshotContext(currencyAddress, context)
                )(
                  _ =>
                    for {
                      snapshotHash <- snapshot.value.hash
                      // Discard node-local publication authority before replacing history.
                      // A crash at any later point can only lose a redundant validator copy;
                      // it cannot revive a superseded randomized proof envelope at startup.
                      _ <- recoverySyncPublicationStorage.discardForCanonicalReplacement
                      _ <- stateChannelBinaryOutboxStorage.discardAllForCanonicalReplacement
                      exactInstalled <- ExactSnapshotStorage.installCanonicalSuffixForRecovery(
                        snapshotStorage,
                        snapshot,
                        context,
                        currencySnapshotCleanupStorage.cleanupCanonicalSuffix(snapshot.ordinal, snapshotHash) >>
                          combinedSnapshotCheckpointFileSystemStorage.deleteAbove(snapshot.ordinal)
                      )
                      _ <- Async[F].raiseUnless(exactInstalled)(
                        new IllegalStateException(
                          s"Failed to install exact Currency artifact/context ordinal=${snapshot.ordinal}; keeping consensus stopped"
                        )
                      )
                      _ <- eventMempool.clear
                      _ <- setCalculatedState(snapshot)
                    } yield (),
                  stateChannelBinarySender.clearPending >> stateChannelBinarySender.enablePublishing
                )
              } yield ()
          }

      run.handleErrorWith { error =>
        consensus.manager.abortObservation >>
          nodeStorage.getNodeState.flatMap { state =>
            if (Download.shouldReanchorAfterFailure(state))
              logger.warn(error)(s"Currency download failed in state=$state; reselecting a fresh latest anchor") >>
                nodeStorage
                  .tryModifyStateGetResult(
                    Set[NodeState](
                      NodeState.DownloadInProgress,
                      NodeState.WaitingForObserving,
                      NodeState.Observing,
                      NodeState.WaitingForReady
                    ),
                    NodeState.WaitingForDownload
                  )
                  .void
            else Applicative[F].unit
          } >> error.raiseError[F, Unit]
      }
    }

    private def setCalculatedState(snapshot: Signed[CurrencyIncrementalSnapshot]): F[Unit] =
      maybeDataApplication.fold(Applicative[F].unit) { dataApplication =>
        implicit val decoder = dataApplication.calculatedStateDecoder

        clusterStorage.getResponsivePeers
          .map(NodeState.ready)
          .map(_.toList)
          .flatMap(Random[F].shuffleList)
          .flatMap {
            case Nil       => new Exception("No peers to fetch off-chain state from").raiseError[F, (SnapshotOrdinal, DataCalculatedState)]
            case peer :: _ => p2pClient.dataApplication.getCalculatedState.run(peer)
          }
          .flatTap {
            case (_, calculatedState) =>
              dataApplication.hashCalculatedState(calculatedState).flatMap { hash =>
                Async[F].raiseUnless(snapshot.dataApplication.map(_.calculatedStateProof).contains(hash))(
                  new Exception("Downloaded calculated state does not match the snapshot proof")
                )
              }
          }
          .flatMap { case (ordinal, state) => dataApplication.setCalculatedState(ordinal, state).void }
      }

    def start: F[DownloadResult] = {
      val retryPolicy = exponentialBackoff[F](1.second).join(limitRetries(5))
      retryingOnAllErrors[DownloadResult](
        policy = retryPolicy,
        onError = (error: Throwable, details: RetryDetails) =>
          logger.error(error)(s"Error fetching latest Currency snapshot attempt=${details.retriesSoFar}; selecting another peer")
      )(peerSelect.select.flatMap((peer: L0Peer) => p2pClient.currencySnapshot.getLatest.run(peer)))
    }

    def observe(result: DownloadResult)(implicit hasher: Hasher[F]): F[(DownloadResult, ObservationLimit)] = {
      val observationLimit = Download.observationLimit(result._1.ordinal, observationOffset.value)

      def go(current: DownloadResult): F[DownloadResult] =
        if (current._1.ordinal === observationLimit) current.pure[F]
        else fetchNextSnapshot(current).flatMap(go)

      consensus.manager.registerForConsensus(observationLimit) >> go(result).map(_ -> observationLimit)
    }

    def fetchNextSnapshot(result: DownloadResult)(implicit hasher: Hasher[F]): F[DownloadResult] = {
      val retryPolicy = limitRetries[F](Download.fetchNextRetryCap).join(constantDelay(fetchSnapshotDelayBetweenTrials))

      def isWorthRetrying(error: Throwable): F[Boolean] = error match {
        case CannotFetchSnapshot | InvalidChain => true.pure[F]
        case _                                  => false.pure[F]
      }

      retryingOnSomeErrors(retryPolicy, isWorthRetrying, retry.noop[F, Throwable]) {
        val (lastSnapshot, lastContext) = result
        fetchSnapshot(none, lastSnapshot.ordinal.next).flatMap { snapshot =>
          lastSnapshot.toHashed[F].flatMap { parent =>
            Async[F].raiseUnless(Validator.isNextSnapshot(parent, snapshot.value))(InvalidChain)
          } >> identifierStorage.get.flatMap { address =>
            currencySnapshotContextFns
              .createContext(
                CurrencySnapshotContext(address, lastContext),
                lastSnapshot,
                snapshot,
                getGlobalSnapshotByOrdinal
              )
              .handleErrorWith(_ => InvalidChain.raiseError[F, CurrencySnapshotContext])
          }
            .map(context => snapshot -> context.snapshotInfo)
        }
      }
    }

    def fetchSnapshot(hash: Option[Hash], ordinal: SnapshotOrdinal)(implicit hasher: Hasher[F]): F[Signed[CurrencyIncrementalSnapshot]] =
      clusterStorage.getResponsivePeers
        .map(NodeState.ready)
        .map(_.toList)
        .flatMap(Random[F].shuffleList)
        .flatTap(_ => logger.info(s"Download currency snapshot hash=${hash.show}, ordinal=${ordinal.show}"))
        .flatMap { peers =>
          type Result = Option[Signed[CurrencyIncrementalSnapshot]]
          (peers, none[Signed[CurrencyIncrementalSnapshot]]).tailRecM[F, Result] {
            case (Nil, result) => result.asRight[(List[Peer], Result)].pure[F]
            case (peer :: tail, _) =>
              p2pClient.currencySnapshot
                .get(ordinal)
                .run(peer)
                .flatMap(_.toHashed[F])
                .map(_.some)
                .handleError(_ => none[Hashed[CurrencyIncrementalSnapshot]])
                .map {
                  case Some(snapshot) if hash.forall(_ === snapshot.hash) => snapshot.signed.some.asRight
                  case _                                                  => (tail, none[Signed[CurrencyIncrementalSnapshot]]).asLeft
                }
          }
        }
        .flatMap(_.liftTo[F](CannotFetchSnapshot))
  }

  case object CannotFetchSnapshot extends NoStackTrace
  case object InvalidChain extends NoStackTrace
}
