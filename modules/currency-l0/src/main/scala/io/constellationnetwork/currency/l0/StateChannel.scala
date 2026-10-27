package io.constellationnetwork.currency.l0

import java.security.KeyPair

import cats.data.OptionT
import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.all._
import cats.{Applicative, MonadThrow, Parallel}

import scala.concurrent.duration._

import io.constellationnetwork.currency.dataApplication.BaseDataApplicationL0Service
import io.constellationnetwork.currency.l0.cli.method.Run
import io.constellationnetwork.currency.l0.metrics.updateFailedConfirmingStateChannelBinaryMetrics
import io.constellationnetwork.currency.l0.modules.{Programs, Services, Storages}
import io.constellationnetwork.currency.schema.globalSnapshotSync.{GlobalSnapshotSync, GlobalSnapshotSyncReference}
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kernel.{:: => _, _}
import io.constellationnetwork.node.shared.domain.snapshot.Validator
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSyncGlobalSnapshotStorage
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.modules.SharedStorages
import io.constellationnetwork.node.shared.snapshot.currency.{CurrencySnapshotEvent, GlobalSnapshotSyncEvent}
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security._
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import fs2.Stream
import io.circe.Json
import org.typelevel.log4cats.slf4j.Slf4jLogger

object StateChannel {

  private val awakePeriod = 10.seconds
  private val recoveryTargetMaxRefreshes = 30

  /** One construction/signing/enqueue path shared by normal publication and the rollback recovery barrier. No custom serialization or hash
    * domain is introduced; the existing GlobalSnapshotSync and Signed.forAsyncHasher paths remain authoritative.
    */
  def publishGlobalSnapshotSync[F[_]: Async: Hasher: SecurityProvider](
    snapshot: Hashed[GlobalIncrementalSnapshot],
    parent: GlobalSnapshotSyncReference,
    session: io.constellationnetwork.schema.cluster.SessionToken,
    selfKeyPair: KeyPair,
    enqueueConsensusEventFn: CurrencySnapshotEvent => Cell[F, StackF, _, Either[CellError, Ω], _]
  ): F[Signed[GlobalSnapshotSync]] = {
    val sync = GlobalSnapshotSync(parent.ordinal, snapshot.ordinal, snapshot.hash, session)
    for {
      signedGlobalSnapshotSync <- Signed.forAsyncHasher(sync, selfKeyPair)
      _ <- enqueueConsensusEventFn(GlobalSnapshotSyncEvent(signedGlobalSnapshotSync)).run()
    } yield signedGlobalSnapshotSync
  }

  /** Selects the Global L0 anchor for the rollback recovery declaration.
    *
    * The declaration pins the first successor's `globalSyncView` to `anchor - syncOffset`. Construction resolves that view's fee context
    * from Global state held by this node (`LastSyncGlobalSnapshotStorage.getCombined`), while the retained window carries hashes only. A
    * lead whose first Global L0 pull is the anchor never holds the target's state, so every first-successor proposal would fail with
    * `ExactGlobalFeeContextUnavailable` until the recovery deadline. Keep processing Global L0 snapshots through the ordinary path until
    * the target's exact state is held; fail closed if it conflicts with the retained window or is still absent after `maxRefreshes`.
    */
  def selectRecoveryGlobalAnchor[F[_]: Async: Hasher](
    refresh: F[Unit],
    lastSyncGlobalSnapshot: LastSyncGlobalSnapshotStorage[F],
    recentGlobalSnapshots: F[List[Hashed[GlobalIncrementalSnapshot]]],
    syncOffset: NonNegLong,
    maxRefreshes: Int = recoveryTargetMaxRefreshes,
    retryDelay: FiniteDuration = awakePeriod
  ): F[(Hashed[GlobalIncrementalSnapshot], SnapshotOrdinal)] = {
    val logger = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

    def attempt(refreshes: Int): F[(Hashed[GlobalIncrementalSnapshot], SnapshotOrdinal)] =
      for {
        _ <- refresh
        globalAnchor <- lastSyncGlobalSnapshot.get.flatMap(
          _.liftTo[F](new IllegalStateException("Cannot arm recovery sync refresh without a current Global L0 anchor"))
        )
        recent <- recentGlobalSnapshots
        selectedTarget <- SnapshotOrdinal(globalAnchor.ordinal.value - syncOffset).liftTo[F](
          new IllegalStateException(s"Recovery sync target underflow anchor=${globalAnchor.ordinal} offset=$syncOffset")
        )
        recentByOrdinal = recent.iterator.map(value => value.ordinal -> value.hash).toMap
        _ <- MonadThrow[F].raiseUnless(
          recentByOrdinal.get(globalAnchor.ordinal).contains(globalAnchor.hash) &&
            recentByOrdinal.contains(selectedTarget)
        )(
          new IllegalStateException(
            s"Recovery sync requires a complete canonical recent window: anchor=${globalAnchor.ordinal} " +
              s"selectedTarget=$selectedTarget available=${recentByOrdinal.keys.toList.sorted.mkString(",")}"
          )
        )
        held <- lastSyncGlobalSnapshot.getCombined(selectedTarget).flatMap(_.traverse { case (snapshot, _) => snapshot.hash })
        selected <- held match {
          case Some(hash) if recentByOrdinal.get(selectedTarget).contains(hash) =>
            (globalAnchor, selectedTarget).pure[F]
          case Some(hash) =>
            MonadThrow[F].raiseError[(Hashed[GlobalIncrementalSnapshot], SnapshotOrdinal)](
              new IllegalStateException(
                s"Recovery sync target Global L0 state conflicts with the canonical recent window: anchor=${globalAnchor.ordinal} " +
                  s"selectedTarget=$selectedTarget held=${hash.show}"
              )
            )
          case None if refreshes < maxRefreshes =>
            logger.info(
              s"Recovery sync target Global L0 state is not held yet: anchor=${globalAnchor.ordinal} selectedTarget=$selectedTarget " +
                s"refresh=$refreshes/$maxRefreshes"
            ) >> Async[F].sleep(retryDelay) >> attempt(refreshes + 1)
          case None =>
            MonadThrow[F].raiseError[(Hashed[GlobalIncrementalSnapshot], SnapshotOrdinal)](
              new IllegalStateException(
                s"Recovery sync target Global L0 state is not held after $refreshes refreshes: anchor=${globalAnchor.ordinal} " +
                  s"selectedTarget=$selectedTarget"
              )
            )
        }
      } yield selected

    attempt(1)
  }

  def run[F[_]: Async: HasherSelector: SecurityProvider: Metrics: Parallel: JsonSerializer: Hasher](
    services: Services[F, Run],
    storages: Storages[F],
    sharedStorages: SharedStorages[F],
    programs: Programs[F],
    dataApplicationService: Option[BaseDataApplicationL0Service[F]],
    selfKeyPair: KeyPair,
    enqueueConsensusEventFn: CurrencySnapshotEvent => Cell[F, StackF, _, Either[CellError, Ω], _]
  )(implicit S: Supervisor[F], stateProofSelector: GlobalStateProofSelector): Stream[F, Unit] = {
    val logger = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

    val globalL0SnapshotProcessing: Stream[F, Unit] =
      Stream
        .awakeEvery[F](awakePeriod)
        .evalMap(_ =>
          performGlobalL0SnapshotProcess(
            storages,
            sharedStorages,
            services,
            dataApplicationService,
            selfKeyPair,
            enqueueConsensusEventFn
          ).handleErrorWith { error =>
            logger.error(error)("Error during global L0 snapshot processing")
          }
        )

    val globalL0PeerDiscovery: Stream[F, Unit] =
      Stream
        .awakeEvery[F](awakePeriod)
        .evalMap(_ =>
          performGlobalL0PeerDiscovery(storages, programs).handleErrorWith { error =>
            logger.error(error)("Error during global L0 peer discovery")
          }
        )

    Stream(globalL0SnapshotProcessing, globalL0PeerDiscovery).parJoin(2)
  }

  def performGlobalL0SnapshotProcess[F[_]: Async: HasherSelector: Metrics: SecurityProvider: Parallel: JsonSerializer: Hasher](
    storages: Storages[F],
    sharedStorages: SharedStorages[F],
    services: Services[F, Run],
    dataApplicationService: Option[BaseDataApplicationL0Service[F]],
    selfKeyPair: KeyPair,
    enqueueConsensusEventFn: CurrencySnapshotEvent => Cell[F, StackF, _, Either[CellError, Ω], _],
    publishSyncEvents: Boolean = true
  )(implicit S: Supervisor[F], stateProofSelector: GlobalStateProofSelector): F[Unit] = {
    val logger = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

    def triggerOnGlobalSnapshotPullHook(snapshot: Hashed[GlobalIncrementalSnapshot], context: GlobalSnapshotInfo): F[Unit] =
      dataApplicationService.traverse_ { service =>
        service
          .onGlobalSnapshotPull(snapshot, context)
          .handleErrorWith(error => logger.error(error)("An unexpected error occurred in onGlobalSnapshotPull"))
      }

    def sendGlobalSnapshotSyncConsensusEvent(snapshot: Hashed[GlobalIncrementalSnapshot]): F[Unit] = {
      val selfPeerId = selfKeyPair.getPublic.toId.toPeerId

      val lastSentGlobalSnapshotSync = OptionT(storages.lastGlobalSnapshotSync.get).orElse {
        OptionT(storages.snapshot.head).flatMapF {
          case (_, info) =>
            info.globalSnapshotSyncView
              .flatMap(_.get(selfPeerId))
              .traverse(GlobalSnapshotSyncReference.of[F])
              .map(_.orElse(GlobalSnapshotSyncReference.empty.some))
        }
      }.value

      (storages.lastGlobalSnapshotSync.getRequiredRecoveryRefresh, storages.recoverySyncPublication.get).tupled.flatMap {
        case (Some(required), _) =>
          val anchorAge = Math.max(0L, snapshot.ordinal.value.value - required.value.globalSnapshotOrdinal.value.value)
          val remaining = Math.max(0L, required.validThroughGlobalParent.value.value - snapshot.ordinal.value.value)
          (Metrics[F].updateGauge("dag_currency_l0_recovery_sync_reset_anchor_age_ordinals", anchorAge) >>
            Metrics[F].updateGauge("dag_currency_l0_recovery_sync_selected_target_remaining_ordinals", remaining)).attempt.void
        case (None, Some(publication)) =>
          val anchorAge = Math.max(0L, snapshot.ordinal.value.value - publication.refresh.globalSnapshotOrdinal.value.value)
          val remaining = Math.max(0L, publication.validThroughGlobalParent.value.value - snapshot.ordinal.value.value)
          (Metrics[F].updateGauge("dag_currency_l0_recovery_sync_reset_anchor_age_ordinals", anchorAge) >>
            Metrics[F].updateGauge("dag_currency_l0_recovery_sync_selected_target_remaining_ordinals", remaining)).attempt.void
        case (None, None) =>
          (lastSentGlobalSnapshotSync, storages.session.getToken).flatMapN {
            case (Some(lastGlobalSnapshotSyncRef), Some(session)) =>
              for {
                signedGlobalSnapshotSync <- publishGlobalSnapshotSync(
                  snapshot,
                  lastGlobalSnapshotSyncRef,
                  session,
                  selfKeyPair,
                  enqueueConsensusEventFn
                )
                _ <- storages.lastGlobalSnapshotSync.set(signedGlobalSnapshotSync)
              } yield ()
            case (Some(_), None) =>
              logger.warn("Couldn't send GlobalSnapshotSyncEvent. Session is missing.")
            case (None, Some(_)) =>
              logger.warn("Couldn't send GlobalSnapshotSyncEvent. Last sent reference is missing")
            case _ =>
              logger.error("Couldn't construct GlobalSnapshotSyncEvent. Last sent reference and session are missing")
          }
      }
    }

    // Use syncFullIfNeeded for atomic initialization - avoids race condition where
    // two concurrent calls both see isEmpty=true and both try to sync
    def ensureMptInitialized(ordinal: SnapshotOrdinal, state: GlobalSnapshotInfo): F[Unit] =
      sharedStorages.mptStore.syncFullIfNeeded[Json](state.allStateEntries[F], ordinal)

    def persistGlobalSnapshot(snapshot: Hashed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): F[Unit] =
      for {
        _ <- storages.globalSnapshotsWithStateFileStorage
          .write(snapshot.ordinal, GlobalSnapshotWithState(snapshot.signed, state))
        _ <- storages.globalSnapshotsWithStateDeltasFileStorage
          .write(snapshot.ordinal, GlobalSnapshotWithStateDeltas(snapshot.signed, state.activeAllowSpends, state.activeTokenLocks))
      } yield ()

    def confirmStateChannelBinaries(snapshot: Hashed[GlobalIncrementalSnapshot]): F[Unit] =
      services.stateChannelBinarySender.confirm(snapshot).handleErrorWith { error =>
        logger.error(error)("Error when confirming state channel binary") >>
          updateFailedConfirmingStateChannelBinaryMetrics() >>
          Async[F].unit
      }

    // Initial download may start from a tip newer than the GL0 snapshot that included a pending recovery binary. The exact inclusion is
    // still inside the same retained window that bounded reset acceptance, so scan that canonical window before deciding the durable
    // outbox is unresolved. No artifact derivation depends on this scan; it only restores an operational publication receipt.
    def confirmRetainedStateChannelBinaries: F[Unit] =
      sharedStorages.lastNGlobalSnapshot.getLastN.flatMap(_.sortBy(_.ordinal).traverse_(confirmStateChannelBinaries))

    def handleInitialSnapshot(snapshot: Hashed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): F[Unit] =
      for {
        _ <- logger.info(s"Initializing global snapshot storages with ordinal=${snapshot.ordinal}")
        _ <- storages.lastSyncGlobalSnapshot.setInitial(snapshot, state)
        _ <- sharedStorages.lastNGlobalSnapshot.setInitialFetchingGL0(snapshot, state, services.globalL0.asLeft.some, none)
        _ <- confirmRetainedStateChannelBinaries
        _ <- sharedStorages.lastGlobalSnapshot.setInitial(snapshot, state)
        _ <- persistGlobalSnapshot(snapshot, state)
        _ <- ensureMptInitialized(snapshot.ordinal, state)
        _ <- triggerOnGlobalSnapshotPullHook(snapshot, state)
        _ <- logger.info(s"Successfully initialized global snapshot storages with ordinal=${snapshot.ordinal}")
      } yield ()

    def handleIncrementalSnapshot(
      snapshot: Hashed[GlobalIncrementalSnapshot],
      lastSnapshot: Hashed[GlobalIncrementalSnapshot],
      lastState: GlobalSnapshotInfo
    ): F[Unit] =
      for {
        _ <- logger.info(s"Processing incremental snapshot ordinal=${snapshot.ordinal}")
        _ <- ensureMptInitialized(lastSnapshot.ordinal, lastState)
        context <- services.globalSnapshotContextFunctions.createContext(
          lastState,
          lastSnapshot.signed,
          snapshot.signed,
          services.globalL0.pullGlobalSnapshot
        )
        _ <- storages.lastSyncGlobalSnapshot.set(snapshot, context)
        _ <- sharedStorages.lastNGlobalSnapshot.set(snapshot, context)
        _ <- sharedStorages.lastGlobalSnapshot.set(snapshot, context)
        _ <- persistGlobalSnapshot(snapshot, context)
        _ <- sendGlobalSnapshotSyncConsensusEvent(snapshot).whenA(publishSyncEvents)
        _ <- triggerOnGlobalSnapshotPullHook(snapshot, context)
        _ <- confirmStateChannelBinaries(snapshot)
      } yield ()

    def processSnapshotList(snapshots: List[Hashed[GlobalIncrementalSnapshot]]): F[Unit] =
      snapshots.tailRecM {
        case Nil =>
          ().asRight[List[Hashed[GlobalIncrementalSnapshot]]].pure[F]

        case snapshot :: nextSnapshots =>
          storages.lastSyncGlobalSnapshot.get.flatMap {
            case Some(lastSnapshot) if !Validator.isNextSnapshot(lastSnapshot, snapshot.signed.value) =>
              logger
                .warn(
                  s"Skipping non-next global snapshot ordinal=${snapshot.ordinal.show} (last=${lastSnapshot.ordinal.show}), dropping ${nextSnapshots.size + 1} remaining"
                )
                .as(().asRight[List[Hashed[GlobalIncrementalSnapshot]]])

            case _ =>
              storages.lastSyncGlobalSnapshot.getCombined.flatMap {
                case Some((lastSnapshot, lastState)) =>
                  handleIncrementalSnapshot(snapshot, lastSnapshot, lastState)
                    .as(nextSnapshots.asLeft[Unit])

                case None =>
                  logger
                    .warn(
                      s"Cannot process global snapshot ordinal=${snapshot.ordinal.show}: lastSyncGlobalSnapshot is empty, dropping ${nextSnapshots.size + 1} remaining"
                    )
                    .as(().asRight[List[Hashed[GlobalIncrementalSnapshot]]])
              }
          }
      }

    services.globalL0.pullGlobalSnapshots.flatMap {
      case Left((snapshot, state)) =>
        handleInitialSnapshot(snapshot, state)

      case Right(Nil) =>
        Applicative[F].unit

      case Right(snapshots) =>
        processSnapshotList(snapshots)
    }
  }

  def performGlobalL0PeerDiscovery[F[_]: Async](
    storages: Storages[F],
    programs: Programs[F]
  ): F[Unit] =
    storages.lastSyncGlobalSnapshot.get.flatMap {
      case None =>
        storages.globalL0Cluster.getRandomPeer.flatMap(p => programs.globalL0PeerDiscovery.discoverFrom(p))

      case Some(latestSnapshot) =>
        programs.globalL0PeerDiscovery.discover(latestSnapshot.signed.proofs.map(_.id).map(PeerId._Id.reverseGet))
    }
}
