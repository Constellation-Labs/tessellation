package org.tessellation

import java.security.KeyPair

import cats.effect._
import cats.effect.std.{Random, Supervisor}
import cats.syntax.show._

import org.tessellation.cli.config.CliMethod
import org.tessellation.cli.parser
import org.tessellation.config.Config
import org.tessellation.config.types.KeyConfig
import org.tessellation.http.p2p.P2PClient
import org.tessellation.infrastructure.db.Database
import org.tessellation.infrastructure.genesis.{Loader => GenesisLoader}
import org.tessellation.infrastructure.gossip.RumorHandlers
import org.tessellation.keytool.KeyStoreUtils
import org.tessellation.kryo.{KryoSerializer, coreKryoRegistrar}
import org.tessellation.modules._
import org.tessellation.resources.MkHttpServer.ServerName
import org.tessellation.resources.{AppResources, MkHttpServer}
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.SecurityProvider

import org.typelevel.log4cats.slf4j.Slf4jLogger

object Main extends IOApp {

  implicit val logger = Slf4jLogger.getLogger[IO]

  implicit class ResourceIO[A](value: IO[A]) {
    def asResource: Resource[IO, A] = Resource.eval { value }
  }

  override def run(args: List[String]): IO[ExitCode] =
    Config.load[IO].flatMap { cfg =>
      logger.info(s"Config loaded") >>
        logger.info(s"App environment: ${cfg.environment}") >>
        parser.parse[IO](args).flatMap { cli =>
          Random.scalaUtilRandom[IO].flatMap { implicit random =>
            SecurityProvider.forAsync[IO].use { implicit securityProvider =>
              loadKeyPair[IO](cfg.keyConfig).flatMap { keyPair =>
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

                        rumorHandler = RumorHandlers.make[IO](storages.cluster, storages.trust).handlers
                        _ <- Daemons.start(storages, services, queues, p2pClient, rumorHandler, nodeId, cfg).asResource

                        api = HttpApi.make[IO](storages, queues, services, programs, cfg.environment)
                        _ <- MkHttpServer[IO].newEmber(ServerName("public"), cfg.httpConfig.publicHttp, api.publicApp)
                        _ <- MkHttpServer[IO].newEmber(ServerName("p2p"), cfg.httpConfig.p2pHttp, api.p2pApp)
                        _ <- MkHttpServer[IO].newEmber(ServerName("cli"), cfg.httpConfig.cliHttp, api.cliApp)

                        _ <- (cli.method match {
                          case CliMethod.RunValidator =>
                            storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin)
                          case CliMethod.RunGenesis =>
                            storages.node.tryModifyState(
                              NodeState.Initial,
                              NodeState.LoadingGenesis,
                              NodeState.GenesisReady
                            ) {
                              GenesisLoader.make[IO].load(cli.genesisPath).flatMap { accounts =>
                                logger.info(s"Genesis accounts: ${accounts.show}")
                              }
                            } >> services.session.createSession
                          case _ => IO.raiseError(new RuntimeException("Wrong CLI method"))
                        }).asResource
                      } yield ()).useForever
                    }
                  }
                }
              }
            }
          }
        }
    }

  private def loadKeyPair[F[_]: Async: SecurityProvider](cfg: KeyConfig): F[KeyPair] =
    KeyStoreUtils
      .readKeyPairFromStore[F](
        cfg.keystore,
        cfg.keyalias.value,
        cfg.storepass.value.toCharArray,
        cfg.keypass.value.toCharArray
      )

}
