package io.constellationnetwork.dag.l1

import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.BuildInfo
import io.constellationnetwork.dag.l1.cli.method._
import io.constellationnetwork.dag.l1.config.types._
import io.constellationnetwork.dag.l1.domain.snapshot.programs.DAGSnapshotProcessor
import io.constellationnetwork.dag.l1.http.p2p.P2PClient
import io.constellationnetwork.dag.l1.infrastructure.block.rumor.handler.blockRumorHandler
import io.constellationnetwork.dag.l1.infrastructure.swap.rumor.handler.allowSpendBlockRumorHandler
import io.constellationnetwork.dag.l1.infrastructure.tokenlock.rumor.handler.tokenLockBlockRumorHandler
import io.constellationnetwork.dag.l1.modules._
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.ext.kryo._
import io.constellationnetwork.node.shared.app.{NodeShared, TessellationIOApp, getMajorityPeerIds}
import io.constellationnetwork.node.shared.ext.pureconfig._
import io.constellationnetwork.node.shared.infrastructure.gossip.{GossipDaemon, RumorHandlers}
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.LastNGlobalSnapshotStorage
import io.constellationnetwork.node.shared.resources.MkHttpServer
import io.constellationnetwork.node.shared.resources.MkHttpServer.ServerName
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.cluster.ClusterId
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.node.NodeState.SessionStarted
import io.constellationnetwork.schema.semver.TessellationVersion
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.shared.{SharedKryoRegistrationIdRange, sharedKryoRegistrar}

import com.monovore.decline.Opts
import eu.timepit.refined.auto._
import eu.timepit.refined.boolean.Or
import eu.timepit.refined.pureconfig._
import pureconfig.generic.auto._
import pureconfig.module.enumeratum._

object Main
    extends TessellationIOApp[Run](
      "dag-l1",
      "DAG L1 node",
      ClusterId("17e78993-37ea-4539-a4f3-039068ea1e92"),
      version = TessellationVersion.unsafeFrom(BuildInfo.version)
    ) {

  val opts: Opts[Run] = cli.method.opts

  protected val configFiles: List[String] = List("dag-l1.conf")

  type KryoRegistrationIdRange = DagL1KryoRegistrationIdRange Or SharedKryoRegistrationIdRange

  val kryoRegistrar: Map[Class[_], KryoRegistrationId[KryoRegistrationIdRange]] =
    dagL1KryoRegistrar.union(sharedKryoRegistrar)

  def run(method: Run, nodeShared: NodeShared[IO, Run]): Resource[IO, Unit] = {
    import nodeShared._

    for {
      cfgR <- loadConfigAs[AppConfigReader].asResource
      cfg = method.appConfig(cfgR, sharedConfig)

      queues <- Queues.make[IO](sharedQueues).asResource
      validators = hasherSelector.withCurrent { implicit hasher =>
        Validators
          .make[IO, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
            cfg.shared,
            seedlist,
            cfg.transactionLimit,
            None,
            Hasher.forKryo[IO],
            None
          )
      }
      storages <- Storages
        .make[IO, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
          sharedStorages,
          method.l0Peer,
          validators.transactionContextual,
          validators.allowSpendContextual,
          validators.tokenLockContextual
        )
        .asResource
      p2pClient = P2PClient.make[IO](
        sharedP2PClient,
        sharedResources.client,
        currencyPathPrefix = "dag"
      )
      maybeMajorityPeerIds <- getMajorityPeerIds[IO](
        nodeShared.prioritySeedlist,
        cfg.priorityPeerIds,
        cfg.environment
      ).asResource
      services = Services.make[IO, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo, Run](
        storages,
        storages.lastSnapshot,
        storages.l0Cluster,
        validators,
        sharedServices,
        p2pClient,
        cfg,
        maybeMajorityPeerIds,
        Hasher.forKryo[IO]
      )

      snapshotProcessor = DAGSnapshotProcessor.make(
        storages.address,
        storages.block,
        sharedStorages.lastGlobalSnapshot,
        sharedStorages.lastNGlobalSnapshot,
        storages.transaction,
        storages.allowSpend,
        storages.tokenLock,
        sharedServices.globalSnapshotContextFns,
        Hasher.forKryo[IO],
        services.globalL0.pullGlobalSnapshot,
        services.globalL0
      )
      programs = Programs.make(sharedPrograms, p2pClient, storages, snapshotProcessor)

      rumorHandler = RumorHandlers
        .make[IO](storages.cluster, services.localHealthcheck, sharedStorages.forkInfo)
        .handlers <+>
        blockRumorHandler[IO](queues.peerBlock) <+>
        allowSpendBlockRumorHandler[IO](queues.allowSpendBlocks) <+>
        tokenLockBlockRumorHandler[IO](queues.tokenLocksBlocks)

      _ <- Daemons
        .start(storages, services)
        .asResource

      api = HttpApi
        .make[IO, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo, Run](
          storages,
          queues,
          keyPair.getPrivate,
          services,
          programs,
          nodeShared.nodeId,
          TessellationVersion.unsafeFrom(BuildInfo.version),
          cfg.http,
          Hasher.forKryo[IO],
          validators,
          sharedConfig.delegatedStaking
        )
      _ <- MkHttpServer[IO].newEmber(ServerName("public"), cfg.http.publicHttp, api.publicApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("p2p"), cfg.http.p2pHttp, api.p2pApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("cli"), cfg.http.cliHttp, api.cliApp)

      stateChannel <- StateChannel
        .make[IO, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo, Run](
          cfg,
          keyPair,
          p2pClient,
          programs,
          queues,
          nodeId,
          services,
          storages,
          validators,
          Hasher.forKryo[IO]
        )
        .asResource

      alignment = GlobalSnapshotAlignment.make[IO, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo, Run](
        services,
        programs,
        storages
      )

      swapRuntime = hasherSelector.withCurrent { implicit hasher =>
        Swap.run(
          cfg.swap,
          storages.cluster,
          storages.l0Cluster,
          storages.lastSnapshot,
          storages.node,
          p2pClient.l0BlockOutputClient,
          p2pClient.swapConsensusClient,
          services,
          storages.allowSpend,
          storages.allowSpendBlock,
          queues,
          validators.allowSpend,
          keyPair,
          nodeId
        )
      }

      tokenLockRuntime = hasherSelector.withCurrent { implicit hasher =>
        TokenLock.run(
          cfg.tokenLock,
          storages.cluster,
          storages.l0Cluster,
          storages.lastSnapshot,
          storages.node,
          p2pClient.l0BlockOutputClient,
          p2pClient.tokenLockConsensusClient,
          services,
          storages.tokenLock,
          storages.tokenLockBlock,
          queues,
          validators.tokenLock,
          keyPair,
          nodeId
        )
      }

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

      _ <- {
        method match {
          case cfg: RunInitialValidator =>
            gossipDaemon.startAsInitialValidator >>
              programs.l0PeerDiscovery.discoverFrom(cfg.l0Peer) >>
              storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin) >>
              services.cluster.createSession >>
              services.session.createSession >>
              storages.node.tryModifyState(SessionStarted, NodeState.Ready) >>
              services.restart.setClusterLeaveRestartMethod(
                RunValidator(
                  cfg.keyStore,
                  cfg.alias,
                  cfg.password,
                  cfg.environment,
                  cfg.httpConfig,
                  cfg.l0Peer,
                  cfg.seedlistPath,
                  cfg.collateralAmount,
                  cfg.trustRatingsPath,
                  cfg.prioritySeedlistPath
                )
              ) >>
              services.restart.setNodeForkedRestartMethod(
                RunValidatorWithJoinAttempt(
                  cfg.keyStore,
                  cfg.alias,
                  cfg.password,
                  cfg.environment,
                  cfg.httpConfig,
                  cfg.l0Peer,
                  cfg.seedlistPath,
                  cfg.collateralAmount,
                  cfg.trustRatingsPath,
                  cfg.prioritySeedlistPath,
                  _
                )
              )

          case cfg: RunValidator =>
            gossipDaemon.startAsRegularValidator >>
              programs.l0PeerDiscovery.discoverFrom(cfg.l0Peer) >>
              storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin) >>
              services.restart.setNodeForkedRestartMethod(
                RunValidatorWithJoinAttempt(
                  cfg.keyStore,
                  cfg.alias,
                  cfg.password,
                  cfg.environment,
                  cfg.httpConfig,
                  cfg.l0Peer,
                  cfg.seedlistPath,
                  cfg.collateralAmount,
                  cfg.trustRatingsPath,
                  cfg.prioritySeedlistPath,
                  _
                )
              )

          case cfg: RunValidatorWithJoinAttempt =>
            gossipDaemon.startAsRegularValidator >>
              programs.l0PeerDiscovery.discoverFrom(cfg.l0Peer) >>
              storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin) >>
              programs.joining.joinOneOf(cfg.majorityForkPeerIds) >>
              services.restart.setClusterLeaveRestartMethod(
                RunValidator(
                  cfg.keyStore,
                  cfg.alias,
                  cfg.password,
                  cfg.environment,
                  cfg.httpConfig,
                  cfg.l0Peer,
                  cfg.seedlistPath,
                  cfg.collateralAmount,
                  cfg.trustRatingsPath,
                  cfg.prioritySeedlistPath
                )
              ) >>
              services.restart.setNodeForkedRestartMethod(
                RunValidatorWithJoinAttempt(
                  cfg.keyStore,
                  cfg.alias,
                  cfg.password,
                  cfg.environment,
                  cfg.httpConfig,
                  cfg.l0Peer,
                  cfg.seedlistPath,
                  cfg.collateralAmount,
                  cfg.trustRatingsPath,
                  cfg.prioritySeedlistPath,
                  _
                )
              )
        }
      }.asResource
      _ <- stateChannel.runtime
        .merge(alignment.runtime)
        .merge(swapRuntime)
        .merge(tokenLockRuntime)
        .compile
        .drain
        .handleErrorWith { error =>
          logger.error(error)("An error occured during state channel runtime") >> error.raiseError[IO, Unit]
        }
        .asResource
    } yield ()
  }
}
