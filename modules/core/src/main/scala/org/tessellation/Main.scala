package org.tessellation

import java.security.KeyPair

import cats.effect._
import cats.effect.std.{Random, Supervisor}
import cats.syntax.applicative._
import cats.syntax.semigroupk._
import cats.syntax.show._

import org.tessellation.cli.method.{Run, RunGenesis, RunValidator}
import org.tessellation.http.p2p.P2PClient
import org.tessellation.infrastructure.db.Database
import org.tessellation.infrastructure.genesis.{Loader => GenesisLoader}
import org.tessellation.infrastructure.logs.LoggerConfigurator
import org.tessellation.infrastructure.trust.handler.trustHandler
import org.tessellation.keytool.KeyStoreUtils
import org.tessellation.kryo.{KryoSerializer, coreKryoRegistrar}
import org.tessellation.modules._
import org.tessellation.resources.MkHttpServer.ServerName
import org.tessellation.resources.{AppResources, MkHttpServer}
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.infrastructure.gossip.RumorHandlers
import org.tessellation.security.SecurityProvider

import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Main
    extends CommandIOApp(
      name = "",
      header = "Constellation Node",
      version = "0.0.x"
    ) {

  implicit val logger = Slf4jLogger.getLogger[IO]

  implicit class ResourceIO[A](value: IO[A]) {
    def asResource: Resource[IO, A] = Resource.eval { value }
  }

  override def main: Opts[IO[ExitCode]] =
    cli.method.opts.map { method =>
      method match {
        case cliCfg: Run =>
          val cfg = cliCfg.appConfig

          LoggerConfigurator.configureLogger[IO](cfg.environment) >>
            logger.info(s"App environment: ${cfg.environment}") >>
            Random.scalaUtilRandom[IO].flatMap { implicit random =>
              SecurityProvider.forAsync[IO].use { implicit securityProvider =>
                loadKeyPair[IO](cliCfg).flatMap { keyPair =>
                  KryoSerializer.forAsync[IO](coreKryoRegistrar).use { implicit kryoPool =>
                    Database.forAsync[IO](cfg.dbConfig).use { implicit database =>
                      Supervisor[IO].use { _ =>
                        (for {
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
                        } yield ()).useForever
                      }
                    }
                  }
                }
              }
            }
        case _ => ExitCode.Error.pure[IO]
      }

    }

  private def loadKeyPair[F[_]: Async: SecurityProvider](cfg: Run): F[KeyPair] =
    KeyStoreUtils
      .readKeyPairFromStore[F](
        cfg.keyStore.value.toString,
        cfg.alias.value.value,
        cfg.password.value.value.toCharArray,
        cfg.password.value.value.toCharArray
      )
}
