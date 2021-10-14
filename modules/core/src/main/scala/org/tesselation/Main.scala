package org.tesselation

import java.security.KeyPair

import cats.effect._
import cats.effect.std.Supervisor
import cats.syntax.show._

import org.tesselation.cli.config.CliMethod
import org.tesselation.cli.parser
import org.tesselation.config.Config
import org.tesselation.config.types.KeyConfig
import org.tesselation.http.p2p.P2PClient
import org.tesselation.infrastructure.db.Migrations
import org.tesselation.infrastructure.db.doobie.{DoobieDataSource, DoobieTransactor}
import org.tesselation.infrastructure.genesis.{Loader => GenesisLoader}
import org.tesselation.keytool.KeyStoreUtils
import org.tesselation.keytool.security.SecurityProvider
import org.tesselation.kryo.{KryoSerializer, coreKryoRegistrar}
import org.tesselation.modules._
import org.tesselation.resources.MkHttpServer.ServerName
import org.tesselation.resources.{AppResources, MkHttpServer}
import org.tesselation.schema.node.NodeState
import org.tesselation.schema.peer.PeerId

import org.typelevel.log4cats.slf4j.Slf4jLogger

object Main extends IOApp {

  implicit val logger = Slf4jLogger.getLogger[IO]

  override def run(args: List[String]): IO[ExitCode] =
    Config.load[IO].flatMap { cfg =>
      logger.info(s"Config loaded") >>
        logger.info(s"App environment: ${cfg.environment}") >>
        parser.parse[IO](args).flatMap { cli =>
          SecurityProvider.forAsync[IO].use { implicit securityProvider =>
            loadKeyPair(cfg.keyConfig).flatMap { keyPair =>
              KryoSerializer.forAsync[IO](coreKryoRegistrar).use { implicit kryoPool =>
                DoobieDataSource.forAsync[IO](cfg.dbConfig).use { implicit dataSource =>
                  DoobieTransactor.forAsync[IO].use { implicit doobieTransactor =>
                    Supervisor[IO].use { _ =>
                      (for {
                        res <- AppResources.make[IO](cfg)
                        nodeId = PeerId.fromPublic(keyPair.getPublic)
                        p2pClient = P2PClient.make[IO](res.client)
                        migrations = Migrations.make[IO]

                        _ <- Resource.eval(migrations.migrate)

                        storages <- Resource.eval(Storages.make[IO])
                        services <- Resource.eval(Services.make[IO](cfg, nodeId, keyPair, storages))
                        programs <- Resource.eval(Programs.make[IO](storages, services, p2pClient, nodeId))

                        api = HttpApi.make[IO](storages, services, programs, cfg.environment)
                        _ <- MkHttpServer[IO].newEmber(ServerName("public"), cfg.httpConfig.publicHttp, api.publicApp)
                        _ <- MkHttpServer[IO].newEmber(ServerName("p2p"), cfg.httpConfig.p2pHttp, api.p2pApp)
                        _ <- MkHttpServer[IO].newEmber(ServerName("cli"), cfg.httpConfig.cliHttp, api.cliApp)

                        _ <- Resource.eval {
                          cli.method match {
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
                          }
                        }
                      } yield ()).useForever
                    }
                  }
                }
              }
            }
          }
        }
    }

  private def loadKeyPair(cfg: KeyConfig): IO[KeyPair] =
    KeyStoreUtils
      .keyPairFromStorePath[IO](
        cfg.keystore,
        cfg.keyalias.value,
        cfg.storepass.value.toCharArray,
        cfg.keypass.value.toCharArray
      )

}
