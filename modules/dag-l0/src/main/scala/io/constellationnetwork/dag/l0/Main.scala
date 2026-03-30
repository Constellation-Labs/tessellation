package io.constellationnetwork.dag.l0

import cats.Parallel
import cats.effect._
import cats.syntax.all._

import io.constellationnetwork.BuildInfo
import io.constellationnetwork.dag.l0.StoragesInitializer.initializeStorages
import io.constellationnetwork.dag.l0.cli.method._
import io.constellationnetwork.dag.l0.config.types._
import io.constellationnetwork.dag.l0.domain.snapshot.ForkRecoveryService
import io.constellationnetwork.dag.l0.http.p2p.P2PClient
import io.constellationnetwork.dag.l0.infrastructure.snapshot.event.GlobalSnapshotEvent
import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema.{Finished, GlobalConsensusOutcome}
import io.constellationnetwork.dag.l0.infrastructure.trust.handler.{ordinalTrustHandler, trustHandler}
import io.constellationnetwork.dag.l0.modules._
import io.constellationnetwork.ext.cats.effect._
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.ext.kryo._
import io.constellationnetwork.node.shared.app.{DagL0, NodeShared, TessellationIOApp}
import io.constellationnetwork.node.shared.domain.collateral.OwnCollateralNotSatisfied
import io.constellationnetwork.node.shared.ext.pureconfig._
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
import io.constellationnetwork.node.shared.infrastructure.genesis.{GenesisFS => GenesisLoader}
import io.constellationnetwork.node.shared.infrastructure.gossip.event._
import io.constellationnetwork.node.shared.infrastructure.gossip.{GossipDaemon, RumorHandlers}
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.GlobalSnapshotLocalFileSystemStorage
import io.constellationnetwork.node.shared.resources.MkHttpServer
import io.constellationnetwork.node.shared.resources.MkHttpServer.ServerName
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.cluster.ClusterId
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.schema.mpt.GlobalStateKey
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.semver.TessellationVersion
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.mpt.producer.InMemoryMerklePatriciaProducer
import io.constellationnetwork.security.signature.Signed

import com.monovore.decline.Opts
import eu.timepit.refined.auto._
import eu.timepit.refined.pureconfig._
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pureconfig.generic.auto._
import pureconfig.module.enumeratum._

object Main
    extends TessellationIOApp[Run](
      name = "dag-l0",
      header = "Tessellation Node",
      version = TessellationVersion.unsafeFrom(BuildInfo.version),
      clusterId = ClusterId("6d7f1d6a-213a-4148-9d45-d7200f555ecf"),
      layer = DagL0
    ) {

  val opts: Opts[Run] = cli.method.opts

  protected val configFiles: List[String] = List("dag-l0.conf")

  type KryoRegistrationIdRange = DagL0KryoRegistrationIdRange

  val kryoRegistrar: Map[Class[_], KryoRegistrationId[KryoRegistrationIdRange]] =
    dagL0KryoRegistrar

  def run(method: Run, nodeShared: NodeShared[IO, Run]): Resource[IO, Unit] = {
    import nodeShared._

    for {
      cfgR <- loadConfigAs[AppConfigReader].asResource
      implicit0(logger: SelfAwareStructuredLogger[IO]) = Slf4jLogger.getLoggerFromName[IO](this.getClass.getName)
      cfg = method.appConfig(cfgR, sharedConfig)
      queues <- Queues.make[IO](sharedQueues).asResource

      p2pClient = P2PClient.make[IO](sharedP2PClient, sharedResources.client, sharedServices.session, sharedConfig.snapshotTimeoutsConfig)
      storages <- Storages
        .make[IO](
          sharedStorages,
          sharedConfig,
          nodeShared.seedlist,
          cfg.snapshot,
          cfg.incremental,
          trustRatings,
          sharedConfig.environment,
          hashSelect
        )
        .asResource
      services <- Services
        .make[IO, Run](
          sharedConfig,
          sharedServices,
          sharedStorages,
          queues,
          storages,
          nodeShared.sharedValidators,
          sharedResources.client,
          sharedServices.session,
          nodeShared.seedlist,
          method.stateChannelAllowanceLists,
          nodeShared.nodeId,
          keyPair,
          cfg,
          Hasher.forKryo[IO],
          nodeShared.loggerBundle
        )
        .asResource

      programs = Programs.make[IO, Run](
        sharedPrograms,
        storages,
        services,
        keyPair,
        cfg,
        cfg.incremental.lastFullGlobalSnapshotOrdinal.getOrElse(cfg.environment, SnapshotOrdinal.MinValue),
        p2pClient,
        sharedServices.globalSnapshotContextFns,
        storages.globalSnapshot,
        sharedStorages.lastNGlobalSnapshot,
        sharedStorages.lastGlobalSnapshot,
        sharedStorages.mptStore
      )

      rumorHandler = RumorHandlers
        .make[IO](storages.cluster, services.localHealthcheck, sharedStorages.forkInfo)
        .handlers <+>
        trustHandler(storages.trust) <+> ordinalTrustHandler(storages.trust) <+> services.consensus.handler

      forkRecoveryService = ForkRecoveryService.make[IO](
        storages.node,
        sharedStorages.lastGlobalSnapshot,
        services.recoveryPeerHint
      )

      eventGossipDaemon <- EventGossipDaemon
        .make[IO, GlobalSnapshotEvent, GlobalStateKey](
          services.eventMempool,
          storages.cluster,
          storages.node,
          sharedResources.gossipClient,
          sharedServices.session,
          config = EventGossipConfig(
            heartbeatInterval = cfg.snapshot.consensus.eventGossipHeartbeatInterval,
            pullInterval = cfg.snapshot.consensus.eventGossipPullInterval
          ),
          getLocalChainTip = Some(forkRecoveryService.getLocalChainTip),
          onForkDetected = Some(forkRecoveryService.onForkDetected)
        )
        .asResource

      _ <- Daemons
        .start(
          storages,
          services,
          programs,
          queues,
          nodeId,
          keyPair,
          cfg,
          hasherSelector,
          eventGossipDaemon
        )
        .asResource

      api <- Resource.eval(
        HttpApi.make[IO, Run](
          storages,
          queues,
          services,
          programs,
          keyPair.getPrivate,
          sharedConfig.environment,
          nodeShared.nodeId,
          TessellationVersion.unsafeFrom(BuildInfo.version),
          cfg.http,
          sharedValidators,
          cfg.shared.delegatedStaking.withdrawalTimeLimit
            .getOrElse(sharedConfig.environment, EpochProgress.MinValue),
          cfg.shared,
          storages.combinedGlobalSnapshotCheckpointStorage,
          getLocalChainTip = Some(forkRecoveryService.getLocalChainTip),
          maybeMarkSeen = Some(eventGossipDaemon.markSeen)
        )
      )

      _ <- MkHttpServer[IO].newEmber(ServerName("public"), cfg.http.publicHttp, api.publicApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("p2p"), cfg.http.p2pHttp, api.p2pApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("cli"), cfg.http.cliHttp, api.cliApp)

      gossipDaemon = GossipDaemon.make[IO](
        storages.rumor,
        queues.rumor,
        storages.cluster,
        p2pClient.gossip,
        rumorHandler,
        nodeShared.sharedValidators.rumorValidator,
        services.localHealthcheck,
        nodeId,
        generation,
        sharedConfig.gossip.daemon,
        services.collateral
      )

      _ <- (method match {
        case m: RunValidator =>
          gossipDaemon.startAsRegularValidator >>
            storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin) >>
            services.restart.setNodeForkedRestartMethod(
              RunValidatorWithJoinAttempt(
                m.keyStore,
                m.alias,
                m.password,
                m.dbConfig,
                m.httpConfig,
                m.environment,
                m.seedlistPath,
                m.collateralAmount,
                m.trustRatingsPath,
                m.prioritySeedlistPath,
                _,
                m.allowanceListPath
              )
            )
        case m: RunValidatorWithJoinAttempt =>
          gossipDaemon.startAsRegularValidator >>
            storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin) >>
            programs.joining.joinOneOf(m.peerToJoinPool) >>
            services.restart.setClusterLeaveRestartMethod(
              RunValidator(
                m.keyStore,
                m.alias,
                m.password,
                m.dbConfig,
                m.httpConfig,
                m.environment,
                m.seedlistPath,
                m.collateralAmount,
                m.trustRatingsPath,
                m.prioritySeedlistPath,
                m.allowanceListPath
              )
            ) >>
            services.restart.setNodeForkedRestartMethod(
              RunValidatorWithJoinAttempt(
                m.keyStore,
                m.alias,
                m.password,
                m.dbConfig,
                m.httpConfig,
                m.environment,
                m.seedlistPath,
                m.collateralAmount,
                m.trustRatingsPath,
                m.prioritySeedlistPath,
                _,
                m.allowanceListPath
              )
            )
        case m: RunRollback =>
          storages.node.tryModifyState(
            NodeState.Initial,
            NodeState.RollbackInProgress,
            NodeState.RollbackDone
          ) {
            programs.rollbackLoader.load(m.rollbackHash, programs.download).flatMap {
              case (snapshotInfo, snapshot) =>
                for {
                  hashedSnapshot <- hasherSelector.withCurrent(implicit hasher => snapshot.toHashed[IO])
                  result <- services.consensus.manager.startFacilitatingAfterRollback(
                    snapshot.ordinal,
                    GlobalConsensusOutcome(
                      snapshot.ordinal,
                      Facilitators(List(nodeId)),
                      RemovedFacilitators.empty,
                      WithdrawnFacilitators.empty,
                      EligibleFacilitators.empty,
                      Finished(snapshot, snapshotInfo, EventTrigger, Candidates.empty, Hash.empty, hashedSnapshot.hash)
                    )
                  )
                } yield result
            }
          } >>
            services.collateral
              .hasCollateral(nodeShared.nodeId)
              .flatMap(OwnCollateralNotSatisfied.raiseError[IO, Unit].unlessA) >>
            gossipDaemon.startAsInitialValidator >>
            services.cluster.createSession >>
            services.session.createSession >>
            storages.node.setNodeState(NodeState.Ready) >>
            services.restart.setClusterLeaveRestartMethod(
              RunValidator(
                m.keyStore,
                m.alias,
                m.password,
                m.dbConfig,
                m.httpConfig,
                m.environment,
                m.seedlistPath,
                m.collateralAmount,
                m.trustRatingsPath,
                m.prioritySeedlistPath,
                m.allowanceListPath
              )
            ) >>
            services.restart.setNodeForkedRestartMethod(
              RunValidatorWithJoinAttempt(
                m.keyStore,
                m.alias,
                m.password,
                m.dbConfig,
                m.httpConfig,
                m.environment,
                m.seedlistPath,
                m.collateralAmount,
                m.trustRatingsPath,
                m.prioritySeedlistPath,
                _,
                m.allowanceListPath
              )
            )
        case m: RunGenesis =>
          storages.node.tryModifyState(
            NodeState.Initial,
            NodeState.LoadingGenesis,
            NodeState.GenesisReady
          ) {
            GenesisLoader.make[IO, GlobalSnapshot].loadBalances(m.genesisPath).flatMap { accounts =>
              val genesis = GlobalSnapshot.mkGenesis(
                accounts.map(a => (a.address, a.balance)).toMap,
                m.startingEpochProgress
              )

              hasherSelector.withCurrent { implicit hasher =>
                Signed
                  .forAsyncHasher[IO, GlobalSnapshot](genesis, keyPair)
                  .flatMap(_.toHashed[IO])
              }.flatMap { hashedGenesis =>
                GlobalSnapshotLocalFileSystemStorage.make[IO](cfg.snapshot.snapshotPath).flatMap {
                  fullGlobalSnapshotLocalFileSystemStorage =>
                    hasherSelector.withCurrent { implicit hasher =>
                      fullGlobalSnapshotLocalFileSystemStorage.write(hashedGenesis.signed) >>
                        GlobalSnapshot.mkFirstIncrementalSnapshot[IO](hashedGenesis).flatMap { firstIncrementalSnapshot =>
                          Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](firstIncrementalSnapshot, keyPair).flatMap {
                            signedFirstIncrementalSnapshot =>
                              for {
                                _ <- services.collateral
                                  .hasCollateral(nodeShared.nodeId)
                                  .flatMap(OwnCollateralNotSatisfied.raiseError[IO, Unit].unlessA)
                                hashedSnapshot <- signedFirstIncrementalSnapshot.toHashed[IO]
                                globalSnapshotInfo = hashedGenesis.info.toGlobalSnapshotInfo
                                _ <- initializeStorages[IO](
                                  storages.globalSnapshot,
                                  sharedStorages.lastNGlobalSnapshot,
                                  sharedStorages.lastGlobalSnapshot,
                                  programs.download,
                                  hashedSnapshot,
                                  globalSnapshotInfo
                                )
                                kvPairs <- globalSnapshotInfo.allStateEntries[IO](
                                  Async[IO],
                                  Parallel[IO],
                                  hasher,
                                  jsonSerializer,
                                  globalStateProofSelector
                                )
                                _ <- sharedStorages.mptStore.syncFull(kvPairs, hashedSnapshot.ordinal)

                                _ <- services.consensus.manager
                                  .startFacilitatingAfterRollback(
                                    signedFirstIncrementalSnapshot.ordinal,
                                    GlobalConsensusOutcome(
                                      signedFirstIncrementalSnapshot.ordinal,
                                      Facilitators(List(nodeId)),
                                      RemovedFacilitators.empty,
                                      WithdrawnFacilitators.empty,
                                      EligibleFacilitators.empty,
                                      Finished(
                                        signedFirstIncrementalSnapshot,
                                        hashedGenesis.info.toGlobalSnapshotInfo,
                                        EventTrigger,
                                        Candidates.empty,
                                        Hash.empty,
                                        hashedSnapshot.hash
                                      )
                                    )
                                  )
                              } yield ()
                          }
                        }
                    }
                }
              }
            }
          } >>
            gossipDaemon.startAsInitialValidator >>
            services.cluster.createSession >>
            services.session.createSession >>
            storages.node.setNodeState(NodeState.Ready) >>
            services.restart.setClusterLeaveRestartMethod(
              RunValidator(
                m.keyStore,
                m.alias,
                m.password,
                m.dbConfig,
                m.httpConfig,
                m.environment,
                m.seedlistPath,
                m.collateralAmount,
                m.trustRatingsPath,
                m.prioritySeedlistPath,
                m.allowanceListPath
              )
            ) >>
            services.restart.setNodeForkedRestartMethod(
              RunValidatorWithJoinAttempt(
                m.keyStore,
                m.alias,
                m.password,
                m.dbConfig,
                m.httpConfig,
                m.environment,
                m.seedlistPath,
                m.collateralAmount,
                m.trustRatingsPath,
                m.prioritySeedlistPath,
                _,
                m.allowanceListPath
              )
            )
      }).asResource
    } yield ()
  }
}
