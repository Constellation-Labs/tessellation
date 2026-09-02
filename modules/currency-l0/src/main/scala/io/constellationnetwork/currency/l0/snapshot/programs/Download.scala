package io.constellationnetwork.currency.l0.snapshot.programs

import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.all._
import cats.{Applicative, MonadThrow}

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

import io.constellationnetwork.currency.dataApplication.storage.CalculatedStateLocalFileSystemStorage
import io.constellationnetwork.currency.dataApplication.{BaseDataApplicationL0Service, DataCalculatedState, L0NodeContext}
import io.constellationnetwork.currency.l0.domain.snapshot.storages.CurrencySnapshotCleanupStorage
import io.constellationnetwork.currency.l0.http.p2p.P2PClient
import io.constellationnetwork.currency.l0.snapshot.CurrencySnapshotConsensus
import io.constellationnetwork.currency.l0.snapshot.services.StateChannelBinarySender
import io.constellationnetwork.currency.l0.snapshot.storage.{
  CurrencyFeeContextReceiptStorage,
  RecoverySyncPublicationStorage,
  StateChannelBinaryOutboxStorage
}
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
import io.constellationnetwork.schema.peer.Peer
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher, HasherSelector}

import eu.timepit.refined.types.numeric.NonNegLong
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.RetryPolicies._
import retry._

/** Stable release/mainnet observe-and-register download flow with current crash-safe storage replacement. */
object Download {

  final case class ObservationPlan(catchUpThrough: SnapshotOrdinal, observationLimit: SnapshotOrdinal)

  final case class PeerTipBehindDownloadedHead(downloadedHead: SnapshotOrdinal, peerTip: SnapshotOrdinal) extends NoStackTrace {
    override def getMessage: String =
      s"Selected peer tip ${peerTip.show} is behind the downloaded snapshot head ${downloadedHead.show} served by that peer"
  }

  final case class CalculatedStateConfigurationMismatch(hasArtifactState: Boolean, hasLocalApplication: Boolean) extends NoStackTrace {
    override def getMessage: String =
      s"Currency calculated-state recovery configuration mismatch: artifact=$hasArtifactState localApplication=$hasLocalApplication"
  }

  final case class CalculatedStateUnavailable(ordinal: SnapshotOrdinal, attemptedPeers: Int) extends NoStackTrace {
    override def getMessage: String =
      s"No Ready peer served the calculated state certified at Currency snapshot ordinal=${ordinal.show}; attemptedPeers=$attemptedPeers"
  }

  final case class CalculatedStateProofMismatch(ordinal: SnapshotOrdinal, actual: Hash, expected: Hash) extends NoStackTrace {
    override def getMessage: String =
      s"Calculated state at Currency ordinal=${ordinal.show} has hash=${actual.show}, expected=${expected.show}"
  }

  final case class CalculatedStateRejected(ordinal: SnapshotOrdinal) extends NoStackTrace {
    override def getMessage: String =
      s"Data application rejected recovered calculated state at Currency ordinal=${ordinal.show}"
  }

  final case class CalculatedStatePersistenceMissing(ordinal: SnapshotOrdinal) extends NoStackTrace {
    override def getMessage: String =
      s"Atomically persisted calculated state is missing at Currency ordinal=${ordinal.show}"
  }

  final case class CalculatedStateCurrentConflict(
    targetOrdinal: SnapshotOrdinal,
    currentOrdinal: SnapshotOrdinal,
    actual: Hash,
    expected: Hash
  ) extends NoStackTrace {
    override def getMessage: String =
      s"Current calculated state cannot be replaced safely: targetOrdinal=${targetOrdinal.show} " +
        s"currentOrdinal=${currentOrdinal.show} actual=${actual.show} expected=${expected.show}"
  }

  private[snapshot] final case class CalculatedStateHooks[F[_], State](
    fetchExact: (SnapshotOrdinal, Hash) => F[State],
    hash: State => F[Hash],
    persistAtomically: (SnapshotOrdinal, State) => F[Unit],
    readPersisted: SnapshotOrdinal => F[Option[State]],
    getCurrent: F[(SnapshotOrdinal, State)],
    setCurrent: (SnapshotOrdinal, State) => F[Boolean]
  )

  private[snapshot] def restoreCalculatedStateSteps[F[_]: MonadThrow, State](
    ordinal: SnapshotOrdinal,
    expectedProof: Option[Hash],
    hooks: Option[CalculatedStateHooks[F, State]]
  ): F[Unit] =
    (expectedProof, hooks) match {
      case (None, None) => Applicative[F].unit
      case (Some(expected), Some(calculatedState)) =>
        def persistAndVerify(state: State): F[State] =
          for {
            _ <- calculatedState.persistAtomically(ordinal, state)
            persisted <- calculatedState
              .readPersisted(ordinal)
              .flatMap(_.liftTo[F](CalculatedStatePersistenceMissing(ordinal)))
            persistedHash <- calculatedState.hash(persisted)
            _ <- CalculatedStateProofMismatch(ordinal, persistedHash, expected)
              .raiseError[F, Unit]
              .whenA(persistedHash =!= expected)
          } yield persisted

        for {
          current <- calculatedState.getCurrent
          (currentOrdinal, currentState) = current
          currentHash <- calculatedState.hash(currentState)
          _ <-
            if (currentOrdinal > ordinal || (currentOrdinal === ordinal && currentHash =!= expected))
              CalculatedStateCurrentConflict(ordinal, currentOrdinal, currentHash, expected).raiseError[F, Unit]
            else if (currentOrdinal === ordinal)
              persistAndVerify(currentState).void
            else
              for {
                state <- calculatedState.fetchExact(ordinal, expected)
                actual <- calculatedState.hash(state)
                _ <- CalculatedStateProofMismatch(ordinal, actual, expected)
                  .raiseError[F, Unit]
                  .whenA(actual =!= expected)
                persisted <- persistAndVerify(state)
                accepted <- calculatedState.setCurrent(ordinal, persisted)
                _ <- CalculatedStateRejected(ordinal).raiseError[F, Unit].unlessA(accepted)
              } yield ()
        } yield ()
      case _ =>
        CalculatedStateConfigurationMismatch(expectedProof.nonEmpty, hooks.nonEmpty).raiseError[F, Unit]
    }

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

  private[snapshot] def observationPlan(
    downloadedHead: SnapshotOrdinal,
    peerTip: SnapshotOrdinal,
    offset: Long
  ): Either[PeerTipBehindDownloadedHead, ObservationPlan] =
    if (peerTip < downloadedHead) PeerTipBehindDownloadedHead(downloadedHead, peerTip).asLeft
    else ObservationPlan(peerTip, observationLimit(peerTip, offset)).asRight

  /** Clear pre-recovery events before advertising the validator as an admission candidate.
    *
    * Once registration is visible, the current committee may select this validator for the next round and synchronously push every event
    * named by its Facility declarations. Clearing after registration can therefore acknowledge those pushes and then erase them before the
    * recovered validator creates the same round. The validator is left waiting forever for events that every incumbent was told it stored.
    */
  private[snapshot] def prepareObservationAdmission[F[_]: MonadThrow](
    clearPreRecoveryEvents: F[Unit],
    registerForConsensus: F[Unit]
  ): F[Unit] =
    clearPreRecoveryEvents >> registerForConsensus

  def make[F[_]: Async: Random](
    p2pClient: P2PClient[F],
    clusterStorage: ClusterStorage[F],
    currencySnapshotContextFns: CurrencySnapshotContextFunctions[F],
    nodeStorage: NodeStorage[F],
    consensus: CurrencySnapshotConsensus[F],
    peerSelect: PeerSelect[F],
    identifierStorage: IdentifierStorage[F],
    maybeDataApplication: Option[(BaseDataApplicationL0Service[F], CalculatedStateLocalFileSystemStorage[F])],
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
    stateChannelBinaryOutboxStorage: StateChannelBinaryOutboxStorage[F],
    feeContextReceiptStorage: CurrencyFeeContextReceiptStorage[F]
  )(implicit l0NodeContext: L0NodeContext[F]): Download[F, CurrencyIncrementalSnapshot] = new Download[F, CurrencyIncrementalSnapshot] {

    private val logger = Slf4jLogger.getLogger[F]
    private val observationOffset = NonNegLong(4L)
    private val fetchSnapshotDelayBetweenTrials = 10.seconds

    type DownloadResult = (Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)
    type ObservationLimit = SnapshotOrdinal
    type DownloadAnchor = (DownloadResult, ObservationPlan)

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
                      _ <- restoreCalculatedState(snapshot)
                      snapshotHash <- snapshot.value.hash
                      // Discard node-local publication authority before replacing history.
                      // A crash at any later point can only lose a redundant validator copy;
                      // it cannot revive a superseded randomized proof envelope at startup.
                      _ <- recoverySyncPublicationStorage.discardForCanonicalReplacement
                      _ <- stateChannelBinaryOutboxStorage.discardAllForCanonicalReplacement
                      _ <- feeContextReceiptStorage.discardAllForCanonicalReplacement
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

    private def restoreCalculatedState(snapshot: Signed[CurrencyIncrementalSnapshot]): F[Unit] =
      (snapshot.dataApplication, maybeDataApplication) match {
        case (None, None) => Applicative[F].unit
        case (Some(dataPart), Some((dataApplication, storage))) =>
          implicit val decoder = dataApplication.calculatedStateDecoder

          def fetchExact(ordinal: SnapshotOrdinal, expectedProof: Hash): F[DataCalculatedState] =
            clusterStorage.getResponsivePeers
              .map(peers => NodeState.ready(peers).toList)
              .flatMap(Random[F].shuffleList)
              .flatMap { peers =>
                def go(remaining: List[Peer]): F[DataCalculatedState] =
                  remaining match {
                    case Nil => Download.CalculatedStateUnavailable(ordinal, peers.size).raiseError[F, DataCalculatedState]
                    case peer :: tail =>
                      p2pClient.dataApplication
                        .getCalculatedState(ordinal)
                        .run(peer)
                        .attempt
                        .flatMap {
                          case Right(Some(state)) =>
                            dataApplication.hashCalculatedState(state).flatMap { actualProof =>
                              if (actualProof === expectedProof) state.pure[F]
                              else
                                logger.warn(
                                  Download.CalculatedStateProofMismatch(ordinal, actualProof, expectedProof)
                                )(
                                  s"Peer ${peer.id.show} served mismatched calculated state; trying another Ready peer"
                                ) >> go(tail)
                            }
                          case Right(None) =>
                            logger.warn(
                              Download.CalculatedStateUnavailable(ordinal, 1)
                            )(
                              s"Peer ${peer.id.show} did not have calculated state ordinal=${ordinal.show}; trying another Ready peer"
                            ) >> go(tail)
                          case Left(error) =>
                            logger.warn(error)(
                              s"Peer ${peer.id.show} could not serve calculated state ordinal=${ordinal.show}; trying another Ready peer"
                            ) >> go(tail)
                        }
                  }

                go(peers)
              }

          val hooks = Download.CalculatedStateHooks[F, DataCalculatedState](
            fetchExact = fetchExact,
            hash = dataApplication.hashCalculatedState,
            persistAtomically = (ordinal, state) => storage.writeAtomically(ordinal, state)(dataApplication.serializeCalculatedState),
            readPersisted =
              ordinal => storage.read(ordinal)(bytes => dataApplication.deserializeCalculatedState(bytes).flatMap(_.liftTo[F])),
            getCurrent = dataApplication.getCalculatedState,
            setCurrent = dataApplication.setCalculatedState
          )

          Download.restoreCalculatedStateSteps(
            snapshot.ordinal,
            dataPart.calculatedStateProof.some,
            hooks.some
          )
        case (artifactState, localApplication) =>
          Download
            .CalculatedStateConfigurationMismatch(artifactState.nonEmpty, localApplication.nonEmpty)
            .raiseError[F, Unit]
      }

    def start: F[DownloadAnchor] = {
      val retryPolicy = exponentialBackoff[F](1.second).join(limitRetries(5))
      retryingOnAllErrors[DownloadAnchor](
        policy = retryPolicy,
        onError = (error: Throwable, details: RetryDetails) =>
          logger.error(error)(s"Error fetching latest Currency snapshot attempt=${details.retriesSoFar}; selecting another peer")
      )(
        peerSelect.select.flatMap { peer =>
          for {
            // Start from the peer's exact live artifact/context instead of a periodic
            // checkpoint. Replaying a stale checkpoint can require deterministic Global
            // dependencies that have already left every Ready peer's bounded history.
            result <- p2pClient.currencySnapshot.getLatestHead.run(peer)
            // The peer can finalize another snapshot while the combined body is in flight.
            // Read its current ordinal after the body arrives, catch up through that small
            // race window, and only then register a future observation window.
            peerTip <- p2pClient.currencySnapshot.getLatestOrdinal.run(peer)
            plan <- Async[F].fromEither(Download.observationPlan(result._1.ordinal, peerTip, observationOffset.value))
          } yield result -> plan
        }
      )
    }

    def observe(anchor: DownloadAnchor)(implicit hasher: Hasher[F]): F[(DownloadResult, ObservationLimit)] = {
      val (result, plan) = anchor

      def go(current: DownloadResult, target: SnapshotOrdinal): F[DownloadResult] =
        if (current._1.ordinal === target) current.pure[F]
        else fetchNextSnapshot(current).flatMap(next => go(next, target))

      for {
        caughtUp <- go(result, plan.catchUpThrough)
        _ <- logger.info(
          s"Caught up Currency recovery head from ordinal=${result._1.ordinal.show} " +
            s"through live peer tip=${caughtUp._1.ordinal.show}; registering future observation=${plan.observationLimit.show}"
        )
        _ <- Download.prepareObservationAdmission(
          eventMempool.clear,
          consensus.manager.registerForConsensus(plan.observationLimit)
        )
        observed <- go(caughtUp, plan.observationLimit)
      } yield observed -> plan.observationLimit
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
        .map(peers => NodeState.ready(peers).toList)
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
