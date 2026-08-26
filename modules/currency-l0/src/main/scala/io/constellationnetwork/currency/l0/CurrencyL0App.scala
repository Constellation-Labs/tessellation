package io.constellationnetwork.currency.l0

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.concurrent.duration._

import io.constellationnetwork.currency.dataApplication.{BaseDataApplicationL0Service, DataTransaction, L0NodeContext}
import io.constellationnetwork.currency.l0.StoragesInitializer.initializeCurrencySnapshotStorages
import io.constellationnetwork.currency.l0.cell.{L0Cell, L0CellInput}
import io.constellationnetwork.currency.l0.cli.method
import io.constellationnetwork.currency.l0.cli.method._
import io.constellationnetwork.currency.l0.config.types._
import io.constellationnetwork.currency.l0.http.p2p.P2PClient
import io.constellationnetwork.currency.l0.modules._
import io.constellationnetwork.currency.l0.node.L0NodeContext
import io.constellationnetwork.currency.l0.snapshot.DataTransactionCodecs
import io.constellationnetwork.currency.l0.snapshot.schema.{CurrencyConsensusOutcome, Finished}
import io.constellationnetwork.currency.l0.snapshot.synchronous._
import io.constellationnetwork.currency.schema.CurrencyStateKey
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.currency.schema.globalSnapshotSync.GlobalSnapshotSyncReference
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.ext.kryo._
import io.constellationnetwork.node.shared.app._
import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, SharedConfig, SnapshotConfig}
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.ext.pureconfig._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
import io.constellationnetwork.node.shared.infrastructure.gossip.event.{ChainTip, EventGossipConfig, EventGossipDaemon}
import io.constellationnetwork.node.shared.infrastructure.gossip.{GossipDaemon, RumorHandlers}
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.snapshot.RecoveryGlobalSnapshotSync
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.LastSentGlobalSnapshotSyncStorage.RequiredRecoveryRefresh
import io.constellationnetwork.node.shared.infrastructure.statechannel.StateChannelAllowanceLists
import io.constellationnetwork.node.shared.resources.MkHttpServer
import io.constellationnetwork.node.shared.resources.MkHttpServer.ServerName
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotEvent
import io.constellationnetwork.node.shared.{NodeSharedOrSharedRegistrationIdRange, nodeSharedKryoRegistrar}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.artifact.SharedArtifact
import io.constellationnetwork.schema.cluster.ClusterId
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.semver.{MetagraphVersion, TessellationVersion}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import com.monovore.decline.Opts
import eu.timepit.refined.auto._
import eu.timepit.refined.pureconfig._
import io.circe.{Decoder => CirceDecoder, Encoder => CirceEncoder}
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pureconfig.generic.auto._
import pureconfig.module.enumeratum._

trait OverridableL0 extends TessellationIOApp[Run] {
  def dataApplication: Option[Resource[IO, BaseDataApplicationL0Service[IO]]] = None

  def rewards(
    implicit sp: SecurityProvider[IO]
  ): Option[Rewards[IO, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotEvent]] = None

  def customArtifacts(
    lastCurrencySnapshot: Signed[CurrencyIncrementalSnapshot]
  ): Option[SortedSet[SharedArtifact]] = None
}

object CurrencyL0App {

  /** Currency L0 uses the release/mainnet bootstrap topology: exactly one operator-controlled `run-rollback` lead starts the flat
    * committee, and validators join through normal candidate admission. Artifact proof signers authenticate the rollback anchor; they do
    * not become the new live committee merely because they signed that historical artifact.
    */
  private[currency] def rollbackBootstrapFacilitators(nodeId: PeerId): List[PeerId] =
    List(nodeId)
}

abstract class CurrencyL0App(
  name: String,
  header: String,
  clusterId: ClusterId,
  tessellationVersion: TessellationVersion,
  metagraphVersion: MetagraphVersion
) extends TessellationIOApp[Run](
      name,
      header,
      clusterId,
      layer = CurrencyL0,
      version = tessellationVersion,
      metagraphVersion = metagraphVersion
    )
    with OverridableL0 {

  val opts: Opts[Run] = method.opts

  protected val configFiles: List[String] = List("currency-l0.conf")

  override protected def loadEffectiveConsensusConfig(method: Run, sharedConfig: SharedConfig): IO[Option[ConsensusConfig]] =
    loadConfigAs[AppConfigReader].flatMap { reader =>
      val appConfig = method.appConfig(reader, sharedConfig)
      SnapshotConfig
        .resolveEffectiveConsensusConfig(appConfig.snapshot, appConfig.environment)
        .map(
          _.copy(
            lastGlobalSnapshotSyncOffset = sharedConfig.lastGlobalSnapshotsSync.syncOffset.value,
            lastGlobalSnapshotsInMemory = sharedConfig.lastGlobalSnapshotsSync.maxLastGlobalSnapshotsInMemory.value,
            currencySnapshotProtocolV1ActivationOrdinal = sharedConfig.fieldsAddedOrdinals
              .currencySnapshotProtocolV1For(appConfig.environment)
              .value
              .value
          )
        )
        .liftTo[IO]
        .map(_.some)
    }

  type KryoRegistrationIdRange = NodeSharedOrSharedRegistrationIdRange

  val kryoRegistrar: Map[Class[_], KryoRegistrationId[KryoRegistrationIdRange]] =
    nodeSharedKryoRegistrar

  def run(method: Run, nodeShared: NodeShared[IO, Run]): Resource[IO, Unit] = {
    import nodeShared._

    for {
      cfgR <- loadConfigAs[AppConfigReader].asResource
      implicit0(logger: SelfAwareStructuredLogger[IO]) = Slf4jLogger.getLoggerFromName[IO](this.getClass.getName)
      cfg = method.appConfig(cfgR, sharedConfig)
      loadedConsensusConfig <- IO
        .fromOption(effectiveConsensusConfig)(new IllegalStateException("Currency L0 effective consensus config was not loaded"))
        .asResource

      dataApplicationService <- dataApplication.sequence.adaptError {
        case error =>
          new RuntimeException(
            s"Data application initialization failed: ${error.getMessage}. ",
            error
          )
      }

      hasherSelectorAlwaysCurrent = HasherSelector.forSyncAlwaysCurrent[IO](hasherSelector.getCurrent)

      queues <- Queues.make[IO](sharedQueues).asResource

      storages <- Storages
        .make[IO](
          sharedConfig,
          sharedStorages,
          cfg.snapshot,
          method.globalL0Peer,
          dataApplicationService,
          hasherSelectorAlwaysCurrent
        )
        .asResource
      p2pClient = P2PClient.make[IO](sharedP2PClient, sharedResources.client, sharedServices.session, sharedConfig)
      maybeAllowanceList = StateChannelAllowanceLists.get(cfg.environment)
      validators = Validators.make[IO](cfg.shared, seedlist, maybeAllowanceList, Hasher.forKryo[IO])
      maybeMajorityPeerIds <- getMajorityPeerIds[IO](
        nodeShared.prioritySeedlist,
        sharedConfig.priorityPeerIds,
        cfg.environment
      ).asResource

      mkCell = (event: CurrencySnapshotEvent) => L0Cell.mkL0Cell(queues.l1Output).apply(L0CellInput.HandleCurrencySnapshotEvent(event))

      services <- Services
        .make[IO, Run](
          sharedConfig,
          p2pClient,
          sharedServices,
          sharedStorages,
          sharedValidators,
          storages,
          sharedResources.client,
          sharedServices.session,
          nodeShared.seedlist,
          nodeShared.nodeId,
          keyPair,
          cfg,
          loadedConsensusConfig,
          dataApplicationService,
          rewards,
          validators.signedValidator,
          sharedServices.globalSnapshotContextFns,
          maybeMajorityPeerIds,
          hasherSelectorAlwaysCurrent,
          maybeAllowanceList,
          nodeShared.customAllowanceList,
          // Validators may restore a durable outbox before their local Currency suffix has
          // been replaced by the cluster's canonical download. Download opens publication
          // only after exact installation and outbox reconciliation. Genesis has no remote
          // suffix to adopt; rollback owns its separate explicit gate below.
          method match {
            case _: RunGenesis | _: CreateGenesis => true
            case _                                => false
          },
          Some(customArtifacts)
        )
        .asResource
      implicit0(nodeContext: L0NodeContext[IO]) = L0NodeContext
        .make[IO](
          storages.snapshot,
          hasherSelectorAlwaysCurrent,
          storages.lastSyncGlobalSnapshot,
          storages.identifier,
          nodeShared.seedlist
        )

      programs = Programs.make[IO, Run](
        keyPair,
        nodeShared.nodeId,
        cfg.globalL0Peer,
        sharedPrograms,
        storages,
        services,
        p2pClient,
        services.snapshotContextFunctions,
        dataApplicationService.zip(storages.calculatedStateStorage),
        nodeShared.seedlist,
        hasherSelectorAlwaysCurrent
      )
      rumorHandler = RumorHandlers
        .make[IO](storages.cluster, services.localHealthcheck)
        .handlers <+>
        services.consensus.handler

      // Chain tip getter used by IHave HTTP endpoint (EventGossipRoutes, passed to HttpApi at line 231).
      // Intentionally NOT passed to EventGossipDaemon -- fork detection is deferred for currency-l0.
      // The combined-checkpoint store is the primary source, but it is only written on the production
      // path, so a node that stays caught up by FOLLOWING/downloading (e.g. a 2nd metagraph-L0 node
      // before it is promoted) has an empty checkpoint even while its currency chain head advances
      // every round. Without an advertised tip the B2 admission gate never witnesses it as at-tip and
      // never promotes it to facilitator -- a deadlock for the joining node. Fall back to the latest
      // currency snapshot we hold so a caught-up follower becomes a witnessable admission candidate.
      getLocalChainTip = storages.combinedCurrencySnapshotCheckpointStorage.getLatestCheckpointInfo.flatMap { info =>
        if (info.hash =!= Hash.empty) ChainTip(info.ordinal, info.hash).some.pure[IO]
        else
          storages.snapshot.headSnapshot.flatMap {
            _.flatTraverse { signed =>
              hasherSelectorAlwaysCurrent.withCurrent { implicit hasher =>
                signed.toHashed[IO].map(hashed => ChainTip(signed.value.ordinal, hashed.hash).some)
              }
            }
          }
      }

      eventGossipDaemon <- {
        implicit val dtEncoder: CirceEncoder[DataTransaction] = DataTransactionCodecs.encoder(dataApplicationService)
        implicit val dtDecoder: CirceDecoder[DataTransaction] = DataTransactionCodecs.decoder(dataApplicationService)
        // TODO: Wire ForkRecoveryService for currency-l0 (deferred).
        // getLocalChainTip and onForkDetected are left as None to avoid chain-tip sampling
        // overhead until the recovery callback is implemented for metagraph nodes.
        EventGossipDaemon
          .make[IO, CurrencySnapshotEvent, CurrencyStateKey](
            storages.eventMempool,
            storages.cluster,
            storages.node,
            sharedResources.gossipClient,
            sharedServices.session,
            config = EventGossipConfig(
              heartbeatInterval = loadedConsensusConfig.eventGossipHeartbeatInterval,
              pullInterval = loadedConsensusConfig.eventGossipPullInterval
            )
          )
          .asResource
      }
      _ <- Daemons
        .start(
          storages,
          services,
          programs,
          queues,
          keyPair,
          services.dataApplication,
          eventGossipDaemon,
          cfg,
          loadedConsensusConfig,
          hasherSelectorAlwaysCurrent,
          sharedServices.stateEntryAtRef
        )
        .asResource

      api <- Resource.eval(
        HttpApi
          .make[IO](
            validators,
            storages,
            services,
            programs,
            keyPair.getPrivate,
            cfg.environment,
            nodeShared.nodeId,
            tessellationVersion,
            cfg.http,
            mkCell,
            services.dataApplication,
            metagraphVersion.some,
            queues,
            sharedConfig,
            storages.combinedCurrencySnapshotCheckpointStorage,
            getLocalChainTip = Some(getLocalChainTip),
            maybeMarkSeen = Some(eventGossipDaemon.markSeen)
          )
      )
      // Alpha.95: env-resolved listener caps; see HttpMaxConnectionsDefaults.
      httpResolved = cfg.http.envResolved(cfg.environment)
      _ <- MkHttpServer[IO].newEmber(ServerName("public"), httpResolved.publicHttp, api.publicApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("p2p"), httpResolved.p2pHttp, api.p2pApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("cli"), httpResolved.cliHttp, api.cliApp)

      gossipDaemon = GossipDaemon.make[IO](
        storages.rumor,
        queues.rumor,
        storages.cluster,
        p2pClient.gossip,
        rumorHandler,
        validators.rumorValidator,
        services.localHealthcheck,
        nodeId,
        generation,
        cfg.gossip.daemon,
        services.collateral
      )

      program <- (method match {
        case m: CreateGenesis =>
          hasherSelectorAlwaysCurrent.withCurrent { implicit hasher =>
            programs.genesis.create(dataApplicationService)(
              m.genesisBalancesPath,
              keyPair
            )
          } >> nodeShared.stopSignal.set(true)

        case other =>
          for {
            _ <- StateChannel.performGlobalL0PeerDiscovery[IO](storages, programs)
            innerProgram <- other match {
              case rv: RunValidator =>
                storages.node.setValidatorMode >>
                  storages.identifier.setInitial(rv.identifier) >>
                  HasherSelector[IO].withCurrent { implicit hs =>
                    StateChannel.performGlobalL0SnapshotProcess(
                      storages,
                      sharedStorages,
                      services,
                      dataApplicationService,
                      keyPair,
                      mkCell
                    )
                  } >>
                  gossipDaemon.startAsRegularValidator >>
                  programs.globalL0PeerDiscovery.discoverFrom(cfg.globalL0Peer) >>
                  storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin) >>
                  services.restart.setNodeForkedRestartMethod(
                    RunValidatorWithJoinAttempt(
                      rv.keyStore,
                      rv.alias,
                      rv.password,
                      rv.httpConfig,
                      rv.environment,
                      rv.seedlistPath,
                      rv.prioritySeedlistPath,
                      rv.collateralAmount,
                      rv.globalL0Peer,
                      rv.identifier,
                      rv.trustRatingsPath,
                      _,
                      rv.allowanceListPath
                    )
                  )

              case m: RunValidatorWithJoinAttempt =>
                storages.node.setValidatorMode >>
                  storages.identifier.setInitial(m.identifier) >>
                  gossipDaemon.startAsRegularValidator >>
                  HasherSelector[IO].withCurrent { implicit hs =>
                    StateChannel.performGlobalL0SnapshotProcess(
                      storages,
                      sharedStorages,
                      services,
                      dataApplicationService,
                      keyPair,
                      mkCell
                    )
                  } >>
                  programs.globalL0PeerDiscovery.discoverFrom(cfg.globalL0Peer) >>
                  storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin) >>
                  programs.joining.joinOneOf(m.majorityForkPeerIds) >>
                  services.restart.setClusterLeaveRestartMethod(
                    RunValidator(
                      m.keyStore,
                      m.alias,
                      m.password,
                      m.httpConfig,
                      m.environment,
                      m.seedlistPath,
                      m.prioritySeedlistPath,
                      m.collateralAmount,
                      m.globalL0Peer,
                      m.identifier,
                      m.trustRatingsPath,
                      m.allowanceListPath
                    )
                  ) >>
                  services.restart.setNodeForkedRestartMethod(
                    RunValidatorWithJoinAttempt(
                      m.keyStore,
                      m.alias,
                      m.password,
                      m.httpConfig,
                      m.environment,
                      m.seedlistPath,
                      m.prioritySeedlistPath,
                      m.collateralAmount,
                      m.globalL0Peer,
                      m.identifier,
                      m.trustRatingsPath,
                      _,
                      m.allowanceListPath
                    )
                  )

              case rr: RunRollback =>
                storages.identifier.setInitial(rr.identifier) >>
                  HasherSelector[IO].withCurrent { implicit hs =>
                    StateChannel.performGlobalL0SnapshotProcess(
                      storages,
                      sharedStorages,
                      services,
                      dataApplicationService,
                      keyPair,
                      mkCell
                    )
                  } >>
                  Ref.of[IO, Option[CurrencyConsensusOutcome]](None).flatMap { pendingSoloOutcome =>
                    storages.node.tryModifyState(
                      NodeState.Initial,
                      NodeState.RollbackInProgress,
                      NodeState.RollbackDone
                    )(hasherSelector.withCurrent { implicit hasher =>
                      for {
                        (currencySnapshot, currencySnapshotInfo, lastBinaryHash) <- programs.rollback.rollback
                        _ <- HasherSelector[IO].withCurrent { implicit hasher =>
                          initializeCurrencySnapshotStorages[IO, Run](
                            storages,
                            currencySnapshot.some,
                            currencySnapshotInfo.some
                          )
                        }
                        // Preserve the stable release/mainnet topology: one controlled rollback lead
                        // starts alone; every run-validator node must register and be admitted by a
                        // completed synchronous round. Proof signers authenticate this anchor but do
                        // not select the new live committee.
                        signers = currencySnapshot.proofs.toSortedSet.toList.map(_.id.toPeerId)
                        bootstrapFacilitators = CurrencyL0App.rollbackBootstrapFacilitators(nodeId)
                        bootstrapMode = "controlled_rollback_lead"
                        _ <- logger
                          .warn(
                            s"Currency L0 rollback recovery-sync refresh requested at ordinal=${currencySnapshot.ordinal}. " +
                              s"The committee is self-only regardless of this compatibility flag; the flag only arms the " +
                              s"operator-controlled deterministic-history refresh. nodeId=${nodeId.value.value.take(8)}"
                          )
                          .whenA(rr.allowSoloConsensus)
                        _ <- Metrics[IO].incrementCounter(
                          "dag_consensus_rollback_bootstrap_total",
                          Seq(Metrics.unsafeLabelName("mode") -> bootstrapMode)
                        )
                        _ <- Metrics[IO].updateGauge(
                          "dag_consensus_rollback_proof_signer_count",
                          signers.size.toLong
                        )
                        _ <- Metrics[IO].updateGauge(
                          "dag_consensus_rollback_bootstrap_facilitator_count",
                          bootstrapFacilitators.size.toLong
                        )
                        bootstrapFacilitatorsHash <- SortedSet.from(bootstrapFacilitators).hash
                        rollbackContext = CurrencySnapshotContext(rr.identifier, currencySnapshotInfo)
                        rollbackOutcome = CurrencyConsensusOutcome(
                          currencySnapshot.ordinal,
                          Facilitators(bootstrapFacilitators),
                          RemovedFacilitators.empty,
                          WithdrawnFacilitators.empty,
                          Finished(
                            currencySnapshot,
                            lastBinaryHash,
                            rollbackContext,
                            EventTrigger,
                            Candidates.empty,
                            bootstrapFacilitatorsHash,
                            none
                          )
                        )
                        _ <-
                          if (rr.allowSoloConsensus) pendingSoloOutcome.set(rollbackOutcome.some)
                          else
                            services.stateChannelBinarySender.clearPending >>
                              services.stateChannelBinarySender.enablePublishing >>
                              services.consensus.manager.startFacilitatingAfterRollback(
                                currencySnapshot.ordinal,
                                rollbackOutcome
                              )
                      } yield ()
                    }) >>
                      gossipDaemon.startAsInitialValidator >>
                      services.cluster.createSession >>
                      services.session.createSession >>
                      pendingSoloOutcome.get.flatMap(_.traverse_ { rollbackOutcome =>
                        val currencySnapshot = rollbackOutcome.finished.signedMajorityArtifact
                        val currencySnapshotInfo = rollbackOutcome.finished.context.snapshotInfo

                        HasherSelector[IO].withCurrent { implicit hasher =>
                          for {
                            _ <- IO.raiseUnless(rollbackOutcome.facilitators.value === List(nodeId))(
                              new IllegalStateException(
                                s"Solo rollback recovery requires exactly self as facilitator, got=${rollbackOutcome.facilitators.value.mkString(",")}"
                              )
                            )
                            // Rollback can outlive the initial Global L0 pull. Refresh the canonical
                            // anchor and its retained window after rollback completes, but suppress
                            // ordinary sync publication until the one required recovery declaration
                            // is constructed below.
                            _ <- StateChannel.performGlobalL0SnapshotProcess(
                              storages,
                              sharedStorages,
                              services,
                              dataApplicationService,
                              keyPair,
                              mkCell,
                              publishSyncEvents = false
                            )
                            globalAnchor <- storages.lastSyncGlobalSnapshot.get.flatMap(
                              _.liftTo[IO](new IllegalStateException("Cannot arm recovery sync refresh without a current Global L0 anchor"))
                            )
                            recentGlobalSnapshots <- sharedStorages.lastNGlobalSnapshot.getLastN
                            selectedTarget <- SnapshotOrdinal(
                              globalAnchor.ordinal.value - cfg.shared.lastGlobalSnapshotsSync.syncOffset
                            ).liftTo[IO](
                              new IllegalStateException(
                                s"Recovery sync target underflow anchor=${globalAnchor.ordinal} offset=${cfg.shared.lastGlobalSnapshotsSync.syncOffset}"
                              )
                            )
                            recentByOrdinal = recentGlobalSnapshots.iterator.map(value => value.ordinal -> value.hash).toMap
                            _ <- IO.raiseUnless(
                              recentByOrdinal.get(globalAnchor.ordinal).contains(globalAnchor.hash) &&
                                recentByOrdinal.contains(selectedTarget)
                            )(
                              new IllegalStateException(
                                s"Recovery sync requires a complete canonical recent window: anchor=${globalAnchor.ordinal} " +
                                  s"selectedTarget=$selectedTarget available=${recentByOrdinal.keys.toList.sorted.mkString(",")}"
                              )
                            )
                            pendingPublication <- storages.recoverySyncPublication.get
                            _ <- pendingPublication match {
                              case Some(publication) if !publication.expired =>
                                IO.raiseError(
                                  new IllegalStateException(
                                    s"A recovery successor is still awaiting canonical Global L0 confirmation: " +
                                      s"binaryHash=${publication.binaryHash} mode=${publication.mode} " +
                                      s"validThrough=${publication.validThroughGlobalParent}. Do not create a competing successor."
                                  )
                                )
                              case _ => IO.unit
                            }
                            session <- storages.session.getToken.flatMap(
                              _.liftTo[IO](new IllegalStateException("Cannot arm recovery sync refresh without a node session"))
                            )
                            inheritedSigned = currencySnapshotInfo.globalSnapshotSyncView.getOrElse(
                              SortedMap.empty[PeerId, Signed[io.constellationnetwork.currency.schema.globalSnapshotSync.GlobalSnapshotSync]]
                            )
                            inheritedReferences <- inheritedSigned.toList.traverse {
                              case (peerId, sync) => GlobalSnapshotSyncReference.of[IO](sync).map(peerId -> _)
                            }.map(entries => SortedMap.from[PeerId, GlobalSnapshotSyncReference](entries))
                            refreshMode = RecoveryGlobalSnapshotSync.classify(nodeId, inheritedReferences)
                            snapshotProtocolV1ActivationOrdinal = sharedConfig.fieldsAddedOrdinals
                              .currencySnapshotProtocolV1For(cfg.environment)
                            _ <- IO.raiseUnless(
                              refreshMode != RecoveryGlobalSnapshotSync.ResetInheritedMultiPeerView ||
                                RecoveryGlobalSnapshotSync.isActivationAuthorized(
                                  selectedTarget,
                                  snapshotProtocolV1ActivationOrdinal
                                )
                            )(
                              new IllegalStateException(
                                s"Recovery sync reset is not authorized before Currency snapshot protocol v1 activation: " +
                                  s"selectedTarget=$selectedTarget activation=$snapshotProtocolV1ActivationOrdinal"
                              )
                            )
                            refreshParent = refreshMode match {
                              case RecoveryGlobalSnapshotSync.Chained(parent) => parent
                              case _                                          => GlobalSnapshotSyncReference.empty
                            }
                            signedRefresh <- StateChannel.publishGlobalSnapshotSync(
                              globalAnchor,
                              refreshParent,
                              session,
                              keyPair,
                              mkCell
                            )
                            _ <- storages.lastGlobalSnapshotSync.set(signedRefresh)
                            requiredEvent = io.constellationnetwork.node.shared.snapshot.currency.GlobalSnapshotSyncEvent(signedRefresh)
                            refreshPresent <- {
                              def awaitInsertion(remaining: Int): IO[Boolean] =
                                storages.eventMempool.size.flatMap { size =>
                                  storages.eventMempool.snapshot(Math.max(1, size)).flatMap { snapshot =>
                                    if (snapshot.events.exists(_.signed.value === requiredEvent)) true.pure[IO]
                                    else if (remaining <= 0) false.pure[IO]
                                    else IO.sleep(100.millis) >> awaitInsertion(remaining - 1)
                                  }
                                }

                              awaitInsertion(20)
                            }
                            _ <- IO.raiseUnless(refreshPresent)(
                              new IllegalStateException("Recovery GlobalSnapshotSync was not present in the event mempool after enqueue")
                            )
                            recoveryHeadroom = Math.max(
                              0L,
                              cfg.shared.lastGlobalSnapshotsSync.maxLastGlobalSnapshotsInMemory.value.toLong - 1L -
                                cfg.shared.lastGlobalSnapshotsSync.syncOffset.value
                            )
                            validThroughGlobalParent = SnapshotOrdinal.unsafeApply(
                              Math.addExact(globalAnchor.ordinal.value.value, recoveryHeadroom)
                            )
                            required = RequiredRecoveryRefresh(
                              signedRefresh,
                              refreshMode,
                              validThroughGlobalParent
                            )
                            _ <- storages.lastGlobalSnapshotSync.armRecoveryRefresh(required)
                            _ <- Metrics[IO].updateGauge(
                              "dag_currency_l0_recovery_sync_refresh_pending",
                              1L,
                              Seq(Metrics.unsafeLabelName("mode") -> refreshMode.metricLabel)
                            )
                            _ <- Metrics[IO].updateGauge(
                              "dag_currency_l0_recovery_sync_construction_guard_armed",
                              1L,
                              Seq(Metrics.unsafeLabelName("mode") -> refreshMode.metricLabel)
                            )
                            _ <- Metrics[IO].incrementCounter(
                              "dag_currency_l0_recovery_sync_refresh_total",
                              Seq(
                                Metrics.unsafeLabelName("mode") -> refreshMode.metricLabel,
                                Metrics.unsafeLabelName("outcome") -> "enqueued"
                              )
                            )
                            _ <- Metrics[IO].updateGauge("dag_currency_l0_recovery_sync_reset_anchor_age_ordinals", 0L)
                            _ <- Metrics[IO].updateGauge(
                              "dag_currency_l0_recovery_sync_selected_target_remaining_ordinals",
                              recoveryHeadroom
                            )
                            _ <- logger.warn(
                              s"RECOVERY_SYNC_REFRESH_ENQUEUED mode=${refreshMode.metricLabel} currencyParent=${currencySnapshot.ordinal} " +
                                s"globalAnchor=${globalAnchor.ordinal} inheritedPeers=${inheritedReferences.size}"
                            )
                            _ <- services.stateChannelBinarySender.clearPending
                            _ <- services.stateChannelBinarySender.enablePublishing
                            _ <- services.consensus.manager.startFacilitatingAfterRollback(
                              currencySnapshot.ordinal,
                              rollbackOutcome
                            )
                          } yield ()
                        }
                      }) >>
                      programs.globalL0PeerDiscovery.discoverFrom(cfg.globalL0Peer) >>
                      storages.node.setNodeState(NodeState.Ready) >>
                      services.restart.setClusterLeaveRestartMethod(
                        RunValidator(
                          rr.keyStore,
                          rr.alias,
                          rr.password,
                          rr.httpConfig,
                          rr.environment,
                          rr.seedlistPath,
                          rr.prioritySeedlistPath,
                          rr.collateralAmount,
                          rr.globalL0Peer,
                          rr.identifier,
                          rr.trustRatingsPath,
                          rr.allowanceListPath
                        )
                      ) >>
                      services.restart.setNodeForkedRestartMethod(
                        RunValidatorWithJoinAttempt(
                          rr.keyStore,
                          rr.alias,
                          rr.password,
                          rr.httpConfig,
                          rr.environment,
                          rr.seedlistPath,
                          rr.prioritySeedlistPath,
                          rr.collateralAmount,
                          rr.globalL0Peer,
                          rr.identifier,
                          rr.trustRatingsPath,
                          _,
                          rr.allowanceListPath
                        )
                      )
                  }

              case m: RunGenesis =>
                storages.node.tryModifyState(
                  NodeState.Initial,
                  NodeState.LoadingGenesis,
                  NodeState.GenesisReady
                )(hasherSelector.withCurrent { implicit hasher =>
                  for {
                    _ <- IO.raiseUnless(nodeShared.seedlist.forall(_.exists(_.peerId === nodeId)))(
                      new IllegalStateException(s"Controlled Currency genesis lead ${nodeId.show} is not seedlist eligible")
                    )
                    (currencySnapshot, currencySnapshotInfo, hashedBinary, identifier) <- programs.genesis.accept(dataApplicationService)(
                      m.genesisPath
                    )
                    _ <- HasherSelector[IO].withCurrent { implicit hasher =>
                      initializeCurrencySnapshotStorages[IO, Run](
                        storages,
                        currencySnapshot.some,
                        currencySnapshotInfo.some
                      )
                    }
                    _ <- StateChannel.performGlobalL0SnapshotProcess(
                      storages,
                      sharedStorages,
                      services,
                      dataApplicationService,
                      keyPair,
                      mkCell
                    )
                    _ <-
                      if (cfg.environment =!= AppEnvironment.Dev) {
                        for {
                          _ <- logger.info(s"Setting owner address filled on path: ${m.metagraphOwnerMessagePath}")
                          maybeOwnerEvent <- services.currencyMessages.validateInitialCurrencyOwner(m.metagraphOwnerMessagePath)
                          _ <- logger.info(s"Owner address set")
                          _ <- maybeOwnerEvent.traverse_ { event =>
                            implicit val dtEnc: CirceEncoder[DataTransaction] = DataTransactionCodecs.encoder(dataApplicationService)
                            hasherSelectorAlwaysCurrent.withCurrent { implicit hasher =>
                              Signed.forAsyncHasher[IO, CurrencySnapshotEvent](event, keyPair).flatMap { signedEvent =>
                                signedEvent.toHashed[IO].flatMap { hashedEvent =>
                                  storages.eventMempool.add(signedEvent).flatMap {
                                    case Right(_) => eventGossipDaemon.publish(hashedEvent)
                                    case Left(_)  => IO.unit
                                  }
                                }
                              }
                            }
                          }
                        } yield ()
                      } else IO.unit
                    genesisFacilitators = List(nodeId)
                    genesisFacilitatorsHash <- SortedSet.from(genesisFacilitators).hash
                    genesisContext = CurrencySnapshotContext(identifier, currencySnapshotInfo)
                    genesisOutcome = CurrencyConsensusOutcome(
                      currencySnapshot.ordinal,
                      Facilitators(genesisFacilitators),
                      RemovedFacilitators.empty,
                      WithdrawnFacilitators.empty,
                      Finished(
                        currencySnapshot,
                        hashedBinary.hash,
                        genesisContext,
                        EventTrigger,
                        Candidates.empty,
                        genesisFacilitatorsHash,
                        none
                      )
                    )
                    _ <- services.consensus.manager.startFacilitatingAfterRollback(
                      currencySnapshot.ordinal,
                      genesisOutcome
                    )
                  } yield ()
                }) >>
                  gossipDaemon.startAsInitialValidator >>
                  services.cluster.createSession >>
                  services.session.createSession >>
                  programs.globalL0PeerDiscovery.discoverFrom(cfg.globalL0Peer) >>
                  (
                    logger.info(s"Setting owner address filled on path: ${m.metagraphOwnerMessagePath}") >>
                      services.currencyMessages
                        .validateInitialCurrencyOwner(m.metagraphOwnerMessagePath)
                        .flatMap(_.traverse_(mkCell(_).run())) >>
                      logger.info(s"Owner address set")
                  ).whenA(cfg.environment === AppEnvironment.Dev) >>
                  storages.node.setNodeState(NodeState.Ready) >>
                  storages.identifier.get.flatMap { identifier =>
                    services.restart.setClusterLeaveRestartMethod(
                      RunValidator(
                        m.keyStore,
                        m.alias,
                        m.password,
                        m.httpConfig,
                        m.environment,
                        m.seedlistPath,
                        m.prioritySeedlistPath,
                        m.collateralAmount,
                        m.globalL0Peer,
                        identifier,
                        m.trustRatingsPath,
                        m.allowanceListPath
                      )
                    ) >>
                      services.restart.setNodeForkedRestartMethod(
                        RunValidatorWithJoinAttempt(
                          m.keyStore,
                          m.alias,
                          m.password,
                          m.httpConfig,
                          m.environment,
                          m.seedlistPath,
                          m.prioritySeedlistPath,
                          m.collateralAmount,
                          m.globalL0Peer,
                          identifier,
                          m.trustRatingsPath,
                          _,
                          m.allowanceListPath
                        )
                      )
                  }

              case _ => IO.unit
            }
            _ <- HasherSelector[IO].withCurrent { implicit hs =>
              StateChannel
                .run[IO](services, storages, sharedStorages, programs, dataApplicationService, keyPair, mkCell)
                .compile
                .drain
            }
          } yield innerProgram
      }).asResource

    } yield program
  }
}
