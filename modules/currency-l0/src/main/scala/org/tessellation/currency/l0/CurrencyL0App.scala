package org.tessellation.currency.l0

import cats.effect.{IO, Resource}
import cats.syntax.semigroupk._
import cats.syntax.traverse._

import org.tessellation.BuildInfo
import org.tessellation.currency.dataApplication.{BaseDataApplicationL0Service, L0NodeContext}
import org.tessellation.currency.l0.cli.method
import org.tessellation.currency.l0.cli.method._
import org.tessellation.currency.l0.http.p2p.P2PClient
import org.tessellation.currency.l0.modules._
import org.tessellation.currency.l0.node.L0NodeContext
import org.tessellation.currency.schema.currency._
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.ext.kryo._
import org.tessellation.schema.cluster.ClusterId
import org.tessellation.schema.node.NodeState
import org.tessellation.sdk.app.{SDK, TessellationIOApp}
import org.tessellation.sdk.domain.rewards.Rewards
import org.tessellation.sdk.infrastructure.gossip.{GossipDaemon, RumorHandlers}
import org.tessellation.sdk.resources.MkHttpServer
import org.tessellation.sdk.resources.MkHttpServer.ServerName
import org.tessellation.sdk.{SdkOrSharedOrKernelRegistrationIdRange, sdkKryoRegistrar}
import org.tessellation.security.SecurityProvider

import com.monovore.decline.Opts

abstract class CurrencyL0App(
  name: String,
  header: String,
  clusterId: ClusterId,
  version: String
) extends TessellationIOApp[Run](
      name,
      header,
      clusterId,
      version = version
    ) {

  val opts: Opts[Run] = method.opts

  type KryoRegistrationIdRange = SdkOrSharedOrKernelRegistrationIdRange

  val kryoRegistrar: Map[Class[_], KryoRegistrationId[KryoRegistrationIdRange]] =
    sdkKryoRegistrar

  def dataApplication: Option[BaseDataApplicationL0Service[IO]]

  def rewards(
    implicit sp: SecurityProvider[IO]
  ): Option[Rewards[IO, CurrencyTransaction, CurrencyBlock, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot]]

  def run(method: Run, sdk: SDK[IO]): Resource[IO, Unit] = {
    import sdk._

    val cfg = method.appConfig

    for {
      queues <- Queues.make[IO](sdkQueues).asResource
      p2pClient = P2PClient.make[IO](sdkP2PClient, sdkResources.client, sdkServices.session)
      storages <- Storages.make[IO](sdkStorages, cfg.snapshot, method.globalL0Peer).asResource
      validators = Validators.make[IO](seedlist)

      implicit0(nodeContext: L0NodeContext[IO]) = L0NodeContext.make[IO](storages.snapshot)

      services <- Services
        .make[IO](
          p2pClient,
          sdkServices,
          storages,
          sdkResources.client,
          sdkServices.session,
          sdk.seedlist,
          sdk.nodeId,
          keyPair,
          cfg,
          dataApplication,
          rewards
        )
        .asResource
      programs = Programs.make[IO](
        keyPair,
        sdk.nodeId,
        cfg.globalL0Peer,
        sdkPrograms,
        storages,
        services,
        p2pClient,
        services.snapshotContextFunctions
      )
      healthChecks <- HealthChecks
        .make[IO](
          storages,
          services,
          programs,
          p2pClient,
          sdkResources.client,
          sdkServices.session,
          cfg.healthCheck,
          sdk.nodeId
        )
        .asResource
      rumorHandler = RumorHandlers.make[IO](storages.cluster, healthChecks.ping, services.localHealthcheck).handlers <+>
        services.consensus.handler
      _ <- Daemons
        .start(storages, services, programs, queues, healthChecks, services.dataApplication)
        .asResource

      api = HttpApi
        .make[IO](
          storages,
          queues,
          services,
          programs,
          healthChecks,
          keyPair.getPrivate,
          cfg.environment,
          sdk.nodeId,
          BuildInfo.version,
          cfg.http,
          services.dataApplication
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
        validators.rumorValidator,
        services.localHealthcheck,
        nodeId,
        generation,
        cfg.gossip.daemon,
        services.collateral
      )

      _ <- (method match {
        case rv: RunValidator =>
          storages.identifier.setInitial(rv.identifier) >>
            gossipDaemon.startAsRegularValidator >>
            programs.globalL0PeerDiscovery.discoverFrom(cfg.globalL0Peer) >>
            storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin)

        case rr: RunRollback =>
          storages.identifier.setInitial(rr.identifier) >>
            storages.node.tryModifyState(
              NodeState.Initial,
              NodeState.RollbackInProgress,
              NodeState.RollbackDone
            )(programs.rollback.rollback) >> gossipDaemon.startAsInitialValidator >>
            services.cluster.createSession >>
            services.session.createSession >>
            programs.globalL0PeerDiscovery.discoverFrom(cfg.globalL0Peer) >>
            storages.node.setNodeState(NodeState.Ready)

        case m: RunGenesis =>
          storages.node.tryModifyState(
            NodeState.Initial,
            NodeState.LoadingGenesis,
            NodeState.GenesisReady
          ) {
            services.dataApplication
              .traverse(_.serializedGenesis)
              .flatMap(programs.genesis.accept(m.genesisPath, _))
          } >> gossipDaemon.startAsInitialValidator >>
            services.cluster.createSession >>
            services.session.createSession >>
            programs.globalL0PeerDiscovery.discoverFrom(cfg.globalL0Peer) >>
            storages.node.setNodeState(NodeState.Ready)
      }).asResource
    } yield ()
  }
}
