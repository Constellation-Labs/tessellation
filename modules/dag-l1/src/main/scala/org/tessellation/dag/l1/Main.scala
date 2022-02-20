package org.tessellation.dag.l1

import cats.effect.{IO, Resource}
import cats.syntax.semigroupk._

import org.tessellation.BuildInfo
import org.tessellation.dag.dagSharedKryoRegistrar
import org.tessellation.dag.l1.cli.method.{Run, RunInitialValidator, RunValidator}
import org.tessellation.dag.l1.http.p2p.P2PClient
import org.tessellation.dag.l1.infrastructure.block.rumor.handler.blockRumorHandler
import org.tessellation.dag.l1.infrastructure.db.Database
import org.tessellation.dag.l1.modules._
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.schema.node.NodeState
import org.tessellation.sdk.app.{SDK, TessellationIOApp}
import org.tessellation.sdk.infrastructure.gossip.RumorHandlers
import org.tessellation.sdk.resources.MkHttpServer
import org.tessellation.sdk.resources.MkHttpServer.ServerName

import com.monovore.decline.Opts

object Main extends TessellationIOApp[Run]("", "DAG L1 node", version = BuildInfo.version) {
  val opts: Opts[Run] = cli.method.opts

  val kryoRegistrar: Map[Class[_], Int] = dagL1KryoRegistrar ++ dagSharedKryoRegistrar

  def run(method: Run, sdk: SDK[IO]): Resource[IO, Unit] = {
    import sdk._

    val cfg = method.appConfig

    Database.forAsync[IO](cfg.db).flatMap { implicit database =>
      for {
        queues <- Queues.make[IO](sdkQueues).asResource
        storages <- Storages.make[IO](cfg.tips, sdkStorages).asResource
        validators = Validators.make[IO](storages, cfg.blockValidator)
        services = Services.make[IO](storages, validators, sdkServices)
        p2pClient = P2PClient.make(sdkP2PClient, sdkResources.client)
        healthChecks <- HealthChecks.make[IO](storages, services, p2pClient, cfg.healthCheck, sdk.nodeId).asResource

        rumorHandler = RumorHandlers.make[IO](storages.cluster, healthChecks.ping).handlers <+>
          blockRumorHandler(queues.peerBlock)

        _ <- Daemons
          .start(storages, services, queues, p2pClient, rumorHandler, nodeId, cfg)
          .asResource

        api = HttpApi.make[IO](storages, queues, keyPair.getPrivate, services, sdkPrograms, sdk.nodeId)
        _ <- MkHttpServer[IO].newEmber(ServerName("public"), cfg.http.publicHttp, api.publicApp)
        _ <- MkHttpServer[IO].newEmber(ServerName("p2p"), cfg.http.p2pHttp, api.p2pApp)
        _ <- MkHttpServer[IO].newEmber(ServerName("cli"), cfg.http.cliHttp, api.cliApp)
        stateChannel = new StateChannel(cfg, keyPair, p2pClient, queues, nodeId, services, storages, validators)
        _ <- {
          method match {
            case _: RunInitialValidator =>
              storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin) >>
                services.session.createSession

            case _: RunValidator =>
              storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin)
          }
        }.asResource
        _ <- stateChannel.runtime.compile.drain.asResource
      } yield ()
    }
  }
}
