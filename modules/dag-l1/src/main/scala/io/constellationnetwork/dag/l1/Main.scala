package io.constellationnetwork.dag.l1

import cats.effect.{IO, Resource}
import cats.syntax.applicativeError._
import cats.syntax.semigroupk._

import io.constellationnetwork.BuildInfo
import io.constellationnetwork.dag.l1.cli.method.{Run, RunInitialValidator, RunValidator}
import io.constellationnetwork.dag.l1.config.types._
import io.constellationnetwork.dag.l1.domain.snapshot.programs.DAGSnapshotProcessor
import io.constellationnetwork.dag.l1.http.p2p.P2PClient
import io.constellationnetwork.dag.l1.infrastructure.block.rumor.handler.blockRumorHandler
import io.constellationnetwork.dag.l1.modules._
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.ext.kryo._
import io.constellationnetwork.node.shared.app.{NodeShared, TessellationIOApp, getMajorityPeerIds}
import io.constellationnetwork.node.shared.ext.pureconfig._
import io.constellationnetwork.node.shared.infrastructure.gossip.{GossipDaemon, RumorHandlers}
import io.constellationnetwork.node.shared.resources.MkHttpServer
import io.constellationnetwork.node.shared.resources.MkHttpServer.ServerName
import io.constellationnetwork.schema.cluster.ClusterId
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.node.NodeState.SessionStarted
import io.constellationnetwork.schema.semver.TessellationVersion
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, GlobalSnapshotStateProof}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.shared.{SharedKryoRegistrationIdRange, sharedKryoRegistrar}

import com.monovore.decline.Opts
import eu.timepit.refined.auto._
import eu.timepit.refined.boolean.Or
import eu.timepit.refined.pureconfig._
import pureconfig.ConfigSource
import pureconfig.generic.auto._
import pureconfig.module.catseffect.syntax._
import pureconfig.module.enumeratum._

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

  def run(method: Run, nodeShared: NodeShared[IO, Run]): Resource[IO, Unit] = {
    import nodeShared._

    for {
      cfgR <- ConfigSource.default.loadF[IO, AppConfigReader]().asResource
      cfg = method.appConfig(cfgR, sharedConfig)

      queues <- Queues.make[IO](sharedQueues).asResource
      validators = Validators
        .make[IO, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
          seedlist,
          cfg.transactionLimit,
          None,
          Hasher.forKryo[IO]
        )
      storages <- Storages
        .make[IO, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
          sharedStorages,
          method.l0Peer,
          validators.transactionContextual
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
      services = Services.make[IO, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
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
        storages.lastSnapshot,
        storages.transaction,
        sharedServices.globalSnapshotContextFns,
        Hasher.forKryo[IO]
      )
      programs = Programs.make(sharedPrograms, p2pClient, storages, snapshotProcessor)

      rumorHandler = RumorHandlers
        .make[IO](storages.cluster, services.localHealthcheck, sharedStorages.forkInfo)
        .handlers <+>
        blockRumorHandler(queues.peerBlock)

      _ <- Daemons
        .start(storages, services)
        .asResource

      api = HttpApi
        .make[IO, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
          storages,
          queues,
          keyPair.getPrivate,
          services,
          programs,
          nodeShared.nodeId,
          TessellationVersion.unsafeFrom(BuildInfo.version),
          cfg.http,
          Hasher.forKryo[IO]
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
          validators,
          Hasher.forKryo[IO]
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
