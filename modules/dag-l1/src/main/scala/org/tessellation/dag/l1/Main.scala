package org.tessellation.dag.l1

import cats.effect.{IO, Resource}
import cats.syntax.semigroupk._

import org.tessellation.BuildInfo
import org.tessellation.dag._
import org.tessellation.dag.l1.cli.method.{Run, RunInitialValidator, RunValidator}
import org.tessellation.dag.l1.http.p2p.P2PClient
import org.tessellation.dag.l1.infrastructure.block.rumor.handler.blockRumorHandler
import org.tessellation.dag.l1.infrastructure.db.Database
import org.tessellation.dag.l1.modules._
import org.tessellation.dag.snapshot.SnapshotOrdinal
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.ext.kryo._
import org.tessellation.schema.cluster.ClusterId
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.node.NodeState.SessionStarted
import org.tessellation.sdk.app.{SDK, TessellationIOApp}
import org.tessellation.sdk.infrastructure.gossip.RumorHandlers
import org.tessellation.sdk.resources.MkHttpServer
import org.tessellation.sdk.resources.MkHttpServer.ServerName

import com.monovore.decline.Opts
import eu.timepit.refined.auto._
import eu.timepit.refined.boolean.Or

object Main
    extends TessellationIOApp[Run](
      "",
      "DAG L1 node",
      ClusterId("17e78993-37ea-4539-a4f3-039068ea1e92"),
      version = BuildInfo.version
    ) {
  val opts: Opts[Run] = cli.method.opts

  type KryoRegistrationIdRange = DagL1KryoRegistrationIdRange Or DagSharedKryoRegistrationIdRange

  val kryoRegistrar: Map[Class[_], KryoRegistrationId[KryoRegistrationIdRange]] =
    dagL1KryoRegistrar.union(dagSharedKryoRegistrar)

  def run(method: Run, sdk: SDK[IO]): Resource[IO, Unit] = {
    import sdk._

    val cfg = method.appConfig

    Database.forAsync[IO](cfg.db).flatMap { implicit database =>
      for {
        queues <- Queues.make[IO](sdkQueues).asResource
        storages <- Storages.make[IO](sdkStorages, method.l0Peer, SnapshotOrdinal.MinValue).asResource
        validators = Validators.make[IO](storages, cfg.blockValidator)
        p2pClient = P2PClient.make(sdkP2PClient, sdkResources.client)
        services = Services.make[IO](storages, validators, sdkServices, p2pClient)
        programs = Programs.make(sdkPrograms, p2pClient, storages)
        healthChecks <- HealthChecks
          .make[IO](storages, services, programs, p2pClient, cfg.healthCheck, sdk.nodeId)
          .asResource

        rumorHandler = RumorHandlers.make[IO](storages.cluster, healthChecks.ping).handlers <+>
          blockRumorHandler(queues.peerBlock)

        _ <- Daemons
          .start(storages, services, queues, p2pClient, rumorHandler, nodeId, cfg)
          .asResource

        api = HttpApi.make[IO](storages, queues, keyPair.getPrivate, services, programs, sdk.nodeId)
        _ <- MkHttpServer[IO].newEmber(ServerName("public"), cfg.http.publicHttp, api.publicApp)
        _ <- MkHttpServer[IO].newEmber(ServerName("p2p"), cfg.http.p2pHttp, api.p2pApp)
        _ <- MkHttpServer[IO].newEmber(ServerName("cli"), cfg.http.cliHttp, api.cliApp)
        stateChannel = new StateChannel(
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
        _ <- {
          method match {
            case cfg: RunInitialValidator =>
              programs.l0PeerDiscovery.discoverFrom(cfg.l0Peer) >>
                storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin) >>
                services.cluster.createSession >>
                services.session.createSession >>
                storages.node.tryModifyState(SessionStarted, NodeState.Ready)

            case cfg: RunValidator =>
              programs.l0PeerDiscovery.discoverFrom(cfg.l0Peer) >>
                storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin)
          }
        }.asResource
        _ <- stateChannel.runtime.compile.drain.asResource
      } yield ()
    }
  }
}
