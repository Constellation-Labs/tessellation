package org.tessellation

import cats.effect._
import cats.syntax.semigroupk._
import cats.syntax.show._

import org.tessellation.cli.method.{Run, RunGenesis, RunValidator}
import org.tessellation.ext.cats.effect._
import org.tessellation.http.p2p.P2PClient
import org.tessellation.infrastructure.db.Database
import org.tessellation.infrastructure.genesis.{Loader => GenesisLoader}
import org.tessellation.infrastructure.trust.handler.trustHandler
import org.tessellation.kryo.coreKryoRegistrar
import org.tessellation.modules._
import org.tessellation.resources.MkHttpServer
import org.tessellation.resources.MkHttpServer.ServerName
import org.tessellation.schema.node.NodeState
import org.tessellation.sdk.app.{SDK, TessellationIOApp}
import org.tessellation.sdk.infrastructure.gossip.RumorHandlers

import com.monovore.decline.Opts

object Main
    extends TessellationIOApp[Run](
      name = "",
      header = "Tessellation Node",
      version = BuildInfo.version
    ) {

  val opts: Opts[Run] = cli.method.opts

  val kryoRegistrar: Map[Class[_], Int] = coreKryoRegistrar

  def run(method: Run, sdk: SDK[IO]): Resource[IO, Unit] = {
    import sdk._

    val cfg = method.appConfig

    Database.forAsync[IO](cfg.dbConfig).flatMap { implicit database =>
      for {
        _ <- IO.unit.asResource
        p2pClient = P2PClient.make[IO](sdkP2PClient, sdkResources.client)
        queues <- Queues.make[IO](sdkQueues).asResource
        storages <- Storages.make[IO](sdkStorages).asResource
        services <- Services.make[IO](sdkServices, queues).asResource
        programs = Programs.make[IO](sdkPrograms, storages, services)

        _ <- services.stateChannelRunner.initializeKnownCells.asResource

        rumorHandler = RumorHandlers.make[IO](storages.cluster).handlers <+> trustHandler(
          storages.trust
        )
        _ <- Daemons
          .start(storages, services, queues, p2pClient, rumorHandler, nodeId, cfg)
          .asResource

        api = HttpApi.make[IO](storages, queues, services, programs, cfg.environment)
        _ <- MkHttpServer[IO].newEmber(ServerName("public"), cfg.httpConfig.publicHttp, api.publicApp)
        _ <- MkHttpServer[IO].newEmber(ServerName("p2p"), cfg.httpConfig.p2pHttp, api.p2pApp)
        _ <- MkHttpServer[IO].newEmber(ServerName("cli"), cfg.httpConfig.cliHttp, api.cliApp)

        _ <- (method match {
          case _: RunValidator =>
            storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin)
          case m: RunGenesis =>
            storages.node.tryModifyState(
              NodeState.Initial,
              NodeState.LoadingGenesis,
              NodeState.GenesisReady
            ) {
              GenesisLoader.make[IO].load(m.genesisPath).flatMap { accounts =>
                logger.info(s"Genesis accounts: ${accounts.show}")
              }
            } >> services.session.createSession

        }).asResource
      } yield ()
    }
  }
}
