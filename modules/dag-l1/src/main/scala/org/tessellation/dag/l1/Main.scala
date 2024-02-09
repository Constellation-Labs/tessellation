package org.tessellation.dag.l1

import cats.effect.{IO, Resource}
import cats.syntax.applicativeError._
import cats.syntax.semigroupk._

import org.tessellation.BuildInfo
import org.tessellation.dag.l1.cli.method.{Run, RunInitialValidator, RunValidator}
import org.tessellation.dag.l1.domain.snapshot.programs.DAGSnapshotProcessor
import org.tessellation.dag.l1.http.p2p.P2PClient
import org.tessellation.dag.l1.infrastructure.block.rumor.handler.blockRumorHandler
import org.tessellation.dag.l1.modules._
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.ext.kryo._
import org.tessellation.node.shared.app.{NodeShared, TessellationIOApp, getMajorityPeerIds}
import org.tessellation.node.shared.infrastructure.gossip.{GossipDaemon, RumorHandlers}
import org.tessellation.node.shared.resources.MkHttpServer
import org.tessellation.node.shared.resources.MkHttpServer.ServerName
import org.tessellation.schema.cluster.ClusterId
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.node.NodeState.SessionStarted
import org.tessellation.schema.semver.TessellationVersion
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, GlobalSnapshotStateProof}
import org.tessellation.shared.{SharedKryoRegistrationIdRange, sharedKryoRegistrar}

import com.monovore.decline.Opts
import eu.timepit.refined.auto._
import eu.timepit.refined.boolean.Or

object Main
    extends TessellationIOApp[Run](
      "dag-l1",
      "DAG L1 node",
      ClusterId("17e78993-37ea-4539-a4f3-039068ea1e92"),
      version = TessellationVersion.unsafeFrom(BuildInfo.version)
    ) {
  val opts: Opts[Run] = cli.method.opts

  type KryoRegistrationIdRange = DagL1KryoRegistrationIdRange Or SharedKryoRegistrationIdRange

  val kryoRegistrar: Map[Class[_], KryoRegistrationId[KryoRegistrationIdRange]] =
    dagL1KryoRegistrar.union(sharedKryoRegistrar)

  def run(method: Run, nodeShared: NodeShared[IO]): Resource[IO, Unit] = {
    import nodeShared._

    val cfg = method.appConfig

    for {
      queues <- Queues.make[IO](sharedQueues).asResource
      storages <- Storages
        .make[IO, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
          sharedStorages,
          method.l0Peer
        )
        .asResource
      validators = Validators
        .make[IO, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
          storages,
          seedlist
        )
      p2pClient = P2PClient.make[IO](
        sharedP2PClient,
        sharedResources.client,
        currencyPathPrefix = "dag"
      )
      maybeMajorityPeerIds <- getMajorityPeerIds[IO](
        nodeShared.prioritySeedlist,
        method.nodeSharedConfig.priorityPeerIds,
        cfg.environment
      ).asResource
      services = Services.make[IO, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
        storages,
        storages.lastSnapshot,
        storages.l0Cluster,
        validators,
        sharedServices,
        p2pClient,
        cfg,
        maybeMajorityPeerIds,
        hashSelect
      )
      snapshotProcessor = DAGSnapshotProcessor.make(
        storages.address,
        storages.block,
        storages.lastSnapshot,
        storages.transaction,
        sharedServices.globalSnapshotContextFns
      )
      programs = Programs.make(sharedPrograms, p2pClient, storages, snapshotProcessor)
      healthChecks <- HealthChecks
        .make[IO, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
          storages,
          services,
          programs,
          p2pClient,
          sharedResources.client,
          sharedServices.session,
          cfg.healthCheck,
          nodeShared.nodeId
        )
        .asResource

      rumorHandler = RumorHandlers
        .make[IO](storages.cluster, healthChecks.ping, services.localHealthcheck, sharedStorages.forkInfo)
        .handlers <+>
        blockRumorHandler(queues.peerBlock)

      _ <- Daemons
        .start(storages, services, healthChecks)
        .asResource

      api = HttpApi
        .make[IO, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
          storages,
          queues,
          keyPair.getPrivate,
          services,
          programs,
          healthChecks,
          nodeShared.nodeId,
          TessellationVersion.unsafeFrom(BuildInfo.version),
          cfg.http
        )
      _ <- MkHttpServer[IO].newEmber(ServerName("public"), cfg.http.publicHttp, api.publicApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("p2p"), cfg.http.p2pHttp, api.p2pApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("cli"), cfg.http.cliHttp, api.cliApp)

      stateChannel <- StateChannel
        .make[IO, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
          cfg,
          keyPair,
          p2pClient,
          programs,
          queues,
          nodeId,
          services,
          storages,
          validators
        )
        .asResource

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
              storages.node.tryModifyState(SessionStarted, NodeState.Ready)

          case cfg: RunValidator =>
            gossipDaemon.startAsRegularValidator >>
              programs.l0PeerDiscovery.discoverFrom(cfg.l0Peer) >>
              storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin)
        }
      }.asResource
      _ <- stateChannel.runtime.compile.drain.handleErrorWith { error =>
        logger.error(error)("An error occured during state channel runtime") >> error.raiseError[IO, Unit]
      }.asResource
    } yield ()
  }
}
