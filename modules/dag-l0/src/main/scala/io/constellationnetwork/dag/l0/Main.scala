package io.constellationnetwork.dag.l0

import cats.effect._
import cats.syntax.all._

import io.constellationnetwork.BuildInfo
import io.constellationnetwork.dag.l0.cli.method._
import io.constellationnetwork.dag.l0.config.types._
import io.constellationnetwork.dag.l0.http.p2p.P2PClient
import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema.{Finished, GlobalConsensusOutcome}
import io.constellationnetwork.dag.l0.infrastructure.trust.handler.{ordinalTrustHandler, trustHandler}
import io.constellationnetwork.dag.l0.modules._
import io.constellationnetwork.ext.cats.effect._
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.ext.kryo._
import io.constellationnetwork.node.shared.app.{NodeShared, TessellationIOApp}
import io.constellationnetwork.node.shared.domain.collateral.OwnCollateralNotSatisfied
import io.constellationnetwork.node.shared.ext.pureconfig._
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
import io.constellationnetwork.node.shared.infrastructure.genesis.{GenesisFS => GenesisLoader}
import io.constellationnetwork.node.shared.infrastructure.gossip.{GossipDaemon, RumorHandlers}
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.GlobalSnapshotLocalFileSystemStorage
import io.constellationnetwork.node.shared.resources.MkHttpServer
import io.constellationnetwork.node.shared.resources.MkHttpServer.ServerName
import io.constellationnetwork.schema.cluster.ClusterId
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.semver.TessellationVersion
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshot, SnapshotOrdinal}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import com.monovore.decline.Opts
import eu.timepit.refined.auto._
import eu.timepit.refined.pureconfig._
import pureconfig.generic.auto._
import pureconfig.module.enumeratum._

object Main
    extends TessellationIOApp[Run](
      name = "dag-l0",
      header = "Tessellation Node",
      version = TessellationVersion.unsafeFrom(BuildInfo.version),
      clusterId = ClusterId("6d7f1d6a-213a-4148-9d45-d7200f555ecf")
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
      cfg = method.appConfig(cfgR, sharedConfig)
      queues <- Queues.make[IO](sharedQueues).asResource

      p2pClient = P2PClient.make[IO](sharedP2PClient, sharedResources.client, sharedServices.session)
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
          Hasher.forKryo[IO]
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
        hashSelect
      )

      rumorHandler = RumorHandlers
        .make[IO](storages.cluster, services.localHealthcheck, sharedStorages.forkInfo)
        .handlers <+>
        trustHandler(storages.trust) <+> ordinalTrustHandler(storages.trust) <+> services.consensus.handler

      _ <- Daemons
        .start(storages, services, programs, queues, nodeId, cfg, hasherSelector)
        .asResource

      api = HttpApi
        .make[IO, Run](
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
          cfg.shared.delegatedStaking
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
                _
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
                m.prioritySeedlistPath
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
                _
              )
            )
        case m: RunRollback =>
          storages.node.tryModifyState(
            NodeState.Initial,
            NodeState.RollbackInProgress,
            NodeState.RollbackDone
          ) {
            programs.rollbackLoader.load(m.rollbackHash).flatMap {
              case (snapshotInfo, snapshot) =>
                hasherSelector.forOrdinal(snapshot.ordinal) { implicit hasher =>
                  storages.globalSnapshot.prepend(snapshot, snapshotInfo)
                } >>
                  services.consensus.manager.startFacilitatingAfterRollback(
                    snapshot.ordinal,
                    GlobalConsensusOutcome(
                      snapshot.ordinal,
                      Facilitators(List(nodeId)),
                      RemovedFacilitators.empty,
                      WithdrawnFacilitators.empty,
                      Finished(snapshot, snapshotInfo, EventTrigger, Candidates.empty, Hash.empty)
                    )
                  )
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
                m.prioritySeedlistPath
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
                _
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

              hasherSelector
                .forOrdinal(genesis.ordinal) { implicit hasher =>
                  Signed
                    .forAsyncHasher[IO, GlobalSnapshot](genesis, keyPair)
                    .flatMap(_.toHashed[IO])
                }
                .flatMap { hashedGenesis =>
                  GlobalSnapshotLocalFileSystemStorage.make[IO](cfg.snapshot.snapshotPath).flatMap {
                    fullGlobalSnapshotLocalFileSystemStorage =>
                      hasherSelector
                        .forOrdinal(genesis.ordinal.next) { implicit hasher =>
                          fullGlobalSnapshotLocalFileSystemStorage.write(hashedGenesis.signed) >>
                            GlobalSnapshot.mkFirstIncrementalSnapshot[IO](hashedGenesis).flatMap { firstIncrementalSnapshot =>
                              Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](firstIncrementalSnapshot, keyPair).flatMap {
                                signedFirstIncrementalSnapshot =>
                                  storages.globalSnapshot.prepend(signedFirstIncrementalSnapshot, hashedGenesis.info) >>
                                    services.collateral
                                      .hasCollateral(nodeShared.nodeId)
                                      .flatMap(OwnCollateralNotSatisfied.raiseError[IO, Unit].unlessA) >>
                                    services.consensus.manager
                                      .startFacilitatingAfterRollback(
                                        signedFirstIncrementalSnapshot.ordinal,
                                        GlobalConsensusOutcome(
                                          signedFirstIncrementalSnapshot.ordinal,
                                          Facilitators(List(nodeId)),
                                          RemovedFacilitators.empty,
                                          WithdrawnFacilitators.empty,
                                          Finished(
                                            signedFirstIncrementalSnapshot,
                                            hashedGenesis.info,
                                            EventTrigger,
                                            Candidates.empty,
                                            Hash.empty
                                          )
                                        )
                                      )

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
                m.prioritySeedlistPath
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
                _
              )
            )
      }).asResource
    } yield ()
  }
}
