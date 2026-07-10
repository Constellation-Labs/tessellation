package io.constellationnetwork.currency.l0

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication._
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
import io.constellationnetwork.currency.schema.CurrencyStateKey
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.ext.kryo._
import io.constellationnetwork.node.shared.app._
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.ext.pureconfig._
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
import io.constellationnetwork.node.shared.infrastructure.gossip.event.{ChainTip, EventGossipConfig, EventGossipDaemon}
import io.constellationnetwork.node.shared.infrastructure.gossip.{GossipDaemon, RumorHandlers}
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.LastCheckpointInfo
import io.constellationnetwork.node.shared.infrastructure.statechannel.StateChannelAllowanceLists
import io.constellationnetwork.node.shared.resources.MkHttpServer.ServerName
import io.constellationnetwork.node.shared.resources.{ConsensusExecutor, MkHttpServer}
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotEvent
import io.constellationnetwork.node.shared.{NodeSharedOrSharedRegistrationIdRange, nodeSharedKryoRegistrar}
import io.constellationnetwork.schema.artifact.SharedArtifact
import io.constellationnetwork.schema.cluster.ClusterId
import io.constellationnetwork.schema.gossip.{Ordinal => GossipOrdinal}
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.semver.{MetagraphVersion, TessellationVersion}
import io.constellationnetwork.schema.{ConsensusOperationalState, SnapshotOrdinal}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import com.monovore.decline.Opts
import eu.timepit.refined.auto._
import eu.timepit.refined.pureconfig._
import fs2.concurrent.SignallingRef
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

  type KryoRegistrationIdRange = NodeSharedOrSharedRegistrationIdRange

  val kryoRegistrar: Map[Class[_], KryoRegistrationId[KryoRegistrationIdRange]] =
    nodeSharedKryoRegistrar

  def run(method: Run, nodeShared: NodeShared[IO, Run]): Resource[IO, Unit] = {
    import nodeShared._

    for {
      cfgR <- loadConfigAs[AppConfigReader].asResource
      implicit0(logger: SelfAwareStructuredLogger[IO]) = Slf4jLogger.getLoggerFromName[IO](this.getClass.getName)
      cfg = method.appConfig(cfgR, sharedConfig)

      dataApplicationService <- dataApplication.sequence.adaptError {
        case error =>
          new RuntimeException(
            s"Data application initialization failed: ${error.getMessage}. ",
            error
          )
      }

      hasherSelectorAlwaysCurrent = HasherSelector.forSyncAlwaysCurrent[IO](hasherSelector.getCurrent)

      queues <- Queues.make[IO](sharedQueues).asResource

      // B2 witness channel ref: see dag-l0 Main for full rationale. Default Map.empty —
      // admission votes don't fire until eventGossipDaemon populates this post-startup.
      peerChainTipsGetterRef <-
        Ref
          .of[IO, IO[Map[PeerId, ChainTip]]](
            Map.empty[PeerId, ChainTip].pure[IO]
          )
          .asResource
      getPeerChainTips = peerChainTipsGetterRef.get.flatten

      storages <- Storages
        .make[IO](sharedConfig, sharedStorages, cfg.snapshot, method.globalL0Peer, dataApplicationService, hasherSelectorAlwaysCurrent)
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

      // Dedicated work-stealing pool for the ConsensusEventLoop consume fiber. Mirrors the
      // dag-l0 setup. Isolates round-timing from HTTP serving load on the default global
      // compute pool. See ConsensusExecutor.
      consensusEc <- ConsensusExecutor.optional[IO](cfg.snapshot.consensus.consensusDispatcherThreads)

      snapshotFeeTransactionsRef <- Ref.of[IO, Map[Hash, Signed[FeeTransaction]]](Map.empty).toResource
      implicit0(nodeContext: L0NodeContext[IO]) = L0NodeContext
        .make[IO](
          storages.snapshot,
          hasherSelectorAlwaysCurrent,
          storages.lastSyncGlobalSnapshot,
          storages.identifier,
          nodeShared.seedlist,
          snapshotFeeTransactionsRef
        )
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
          dataApplicationService,
          rewards,
          validators.signedValidator,
          sharedServices.globalSnapshotContextFns,
          maybeMajorityPeerIds,
          hasherSelectorAlwaysCurrent,
          maybeAllowanceList,
          nodeShared.customAllowanceList,
          mkCell,
          Some(customArtifacts),
          queues,
          getPeerChainTips,
          snapshotFeeTransactionsRef,
          consensusEc
        )
        .asResource

      programs = Programs.make[IO, Run](
        keyPair,
        nodeShared.nodeId,
        cfg.globalL0Peer,
        sharedPrograms,
        sharedStorages,
        storages,
        services,
        p2pClient,
        services.snapshotContextFunctions,
        dataApplicationService.zip(storages.calculatedStateStorage)
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
              heartbeatInterval = cfg.snapshot.consensus.eventGossipHeartbeatInterval,
              pullInterval = cfg.snapshot.consensus.eventGossipPullInterval
            )
          )
          .asResource
      }
      // B2 witness channel: publish peer chain tips into the Ref that consensus reads from.
      _ <- Resource.eval(peerChainTipsGetterRef.set(eventGossipDaemon.getPeerChainTips))

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
                      hashedSnapshot <- currencySnapshot.toHashed[IO]
                      // Derive Facilitators/EligibleFacilitators from the signed snapshot's proofs so
                      // every node rolling back seeds an IDENTICAL outcome. If this node was NOT a
                      // signer, fall back to self-only so it can solo-produce. See dag-l0 Main.scala
                      // for full rationale.
                      signers = currencySnapshot.proofs.toSortedSet.toList.map(_.id.toPeerId)
                      bootstrapFacilitators = if (signers.contains(nodeId)) signers else List(nodeId)
                      // Restore consensus-derived peer-behavior counters from the rollback
                      // snapshot if present. Older snapshots have `peerHistory = None` and the
                      // cluster bootstraps from zero just as before. See dag-l0 mirror for the
                      // known one-round off-by-one (we accept it; drift is below chronic floors).
                      seedOperational = currencySnapshot.value.peerHistory.getOrElse(ConsensusOperationalState.empty)
                      seedPeerQuality = SortedMap.from(seedOperational.perPeer.iterator.collect {
                        case (pid, r) if r.quality != ((0, 0)) => pid -> r.quality
                      })
                      seedRemovalPenalties = SortedMap.from(seedOperational.perPeer.iterator.collect {
                        case (pid, r) if r.removalPenalty > 0 => pid -> r.removalPenalty
                      })
                      seedCumulativeMissCounts = SortedMap.from(seedOperational.perPeer.iterator.collect {
                        case (pid, r) if r.cumulativeMissCount > 0L => pid -> r.cumulativeMissCount
                      })
                      seedReadmissionCountdown = SortedMap.from(seedOperational.perPeer.iterator.collect {
                        case (pid, r) if r.readmissionCountdown > 0 => pid -> r.readmissionCountdown
                      })
                      seedDeferralCountdown = SortedMap.from(seedOperational.perPeer.iterator.collect {
                        case (pid, r) if r.deferralCountdown > 0 => pid -> r.deferralCountdown
                      })
                      // v16: per-peer cumulative view-change-caused. Mirror of dag-l0 seed
                      // pattern; see dag-l0 Main for full rationale. viewChangesCaused is
                      // Option[Long] for pre-v16 back-compat at decode time.
                      seedPeerViewChanges = SortedMap.from(seedOperational.perPeer.iterator.flatMap {
                        case (pid, r) => r.viewChangesCaused.filter(_ > 0L).map(v => pid -> v)
                      })
                      rollbackRecentProofSizes =
                        if (seedOperational.recentProofSizes.nonEmpty) seedOperational.recentProofSizes
                        else
                          SortedMap(
                            currencySnapshot.ordinal -> currencySnapshot.proofs.size.toInt
                          )
                      // Recent-signers window unwrap mirror of dag-l0 Main.scala.
                      seedRecentSigners = seedOperational.recentSigners.getOrElse(SortedMap.empty[SnapshotOrdinal, SortedSet[PeerId]])
                      // v19: per-peer tier classification seeded from PerPeerOperationalRecord.tier.
                      // Mirror of dag-l0 Main.scala.
                      seedPeerTiers = SortedMap.from(seedOperational.perPeer.iterator.flatMap {
                        case (pid, r) => r.tier.map(t => pid -> t)
                      })
                      seedActiveAdmissionScores = SortedMap.from(seedOperational.perPeer.iterator.flatMap {
                        case (pid, r) => r.activeAdmissionScore.filter(_ > 0).map(score => pid -> score)
                      })
                      // v19 phase 2: view-from-time window unwrap mirror of dag-l0 Main.scala.
                      seedRecentRoundEndTimes =
                        seedOperational.recentRoundEndTimes.getOrElse(SortedMap.empty[SnapshotOrdinal, Long])
                      _ <- services.consensus.manager.startFacilitatingAfterRollback(
                        currencySnapshot.ordinal,
                        CurrencyConsensusOutcome(
                          currencySnapshot.ordinal,
                          Facilitators(bootstrapFacilitators),
                          RemovedFacilitators.empty,
                          WithdrawnFacilitators.empty,
                          EligibleFacilitators(bootstrapFacilitators),
                          Finished(
                            currencySnapshot,
                            lastBinaryHash,
                            CurrencySnapshotContext(rr.identifier, currencySnapshotInfo),
                            EventTrigger,
                            Candidates.empty,
                            Hash.empty,
                            hashedSnapshot.hash
                          ),
                          removalPenalties = seedRemovalPenalties,
                          deferralCountdown = seedDeferralCountdown,
                          peerQuality = seedPeerQuality,
                          cumulativeMissCounts = seedCumulativeMissCounts,
                          recentProofSizes = rollbackRecentProofSizes,
                          readmissionCountdown = seedReadmissionCountdown,
                          peerViewChanges = seedPeerViewChanges,
                          recentSigners = seedRecentSigners,
                          peerTiers = seedPeerTiers,
                          activeAdmissionScores = seedActiveAdmissionScores,
                          recentRoundEndTimes = seedRecentRoundEndTimes
                        )
                      )
                    } yield ()
                  }) >>
                  gossipDaemon.startAsInitialValidator >>
                  services.cluster.createSession >>
                  services.session.createSession >>
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

              case m: RunGenesis =>
                storages.node.tryModifyState(
                  NodeState.Initial,
                  NodeState.LoadingGenesis,
                  NodeState.GenesisReady
                )(hasherSelector.withCurrent { implicit hasher =>
                  for {
                    (currencySnapshot, currencySnapshotInfo, hash, identifier) <- programs.genesis.accept(dataApplicationService)(
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
                    hashedSnapshot <- currencySnapshot.toHashed[IO]
                    genesisSigners = currencySnapshot.proofs.toSortedSet.toList.map(_.id.toPeerId)
                    // Genesis path — seed window with the genesis snapshot's proof count.
                    // See dag-l0 mirror for rationale.
                    genesisRecentProofSizes = SortedMap(
                      currencySnapshot.ordinal -> currencySnapshot.proofs.size.toInt
                    )
                    _ <- services.consensus.manager.startFacilitatingAfterRollback(
                      currencySnapshot.ordinal,
                      CurrencyConsensusOutcome(
                        currencySnapshot.ordinal,
                        Facilitators(genesisSigners),
                        RemovedFacilitators.empty,
                        WithdrawnFacilitators.empty,
                        EligibleFacilitators(genesisSigners),
                        Finished(
                          currencySnapshot,
                          hash,
                          CurrencySnapshotContext(identifier, currencySnapshotInfo),
                          EventTrigger,
                          Candidates.empty,
                          Hash.empty,
                          hashedSnapshot.hash
                        ),
                        recentProofSizes = genesisRecentProofSizes
                      )
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
