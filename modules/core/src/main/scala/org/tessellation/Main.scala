package org.tessellation

import java.security.KeyPair

import cats.effect._
import cats.effect.std.Random
import cats.syntax.semigroupk._
import cats.syntax.show._

import org.tessellation.cli.method.{Run, RunGenesis, RunValidator}
import org.tessellation.ext.cats.effect._
import org.tessellation.http.p2p.P2PClient
import org.tessellation.infrastructure.db.Database
import org.tessellation.infrastructure.genesis.{Loader => GenesisLoader}
import org.tessellation.infrastructure.trust.handler.trustHandler
import org.tessellation.kryo.{KryoSerializer, coreKryoRegistrar}
import org.tessellation.modules._
import org.tessellation.resources.MkHttpServer.ServerName
import org.tessellation.resources.{AppResources, MkHttpServer}
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.app.TessellationIOApp
import org.tessellation.sdk.infrastructure.gossip.RumorHandlers
import org.tessellation.security.SecurityProvider

import com.monovore.decline.Opts

object Main
    extends TessellationIOApp[Run](
      name = "",
      header = "Tessellation Node",
      version = "0.0.x"
    ) {

  val opts: Opts[Run] = cli.method.opts

  val kryoRegistrar: Map[Class[_], Int] = coreKryoRegistrar

  def run(method: Run)(
    implicit random: Random[IO],
    securityProvider: SecurityProvider[IO],
    keyPair: KeyPair,
    kryoPool: KryoSerializer[IO]
  ): Resource[IO, Unit] = {
    val cfg = method.appConfig

    Database.forAsync[IO](cfg.dbConfig).flatMap { implicit database =>
      for {
        res <- AppResources.make[IO](cfg)
        nodeId = PeerId.fromPublic(keyPair.getPublic)
        _ <- logger.info(s"This peerId ${nodeId.show}").asResource
        p2pClient = P2PClient.make[IO](res.client)
        queues <- Queues.make[IO].asResource
        storages <- Storages.make[IO](cfg).asResource
        services <- Services.make[IO](cfg, nodeId, keyPair, storages, queues).asResource
        programs <- Programs.make[IO](cfg, storages, services, p2pClient, nodeId).asResource

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
