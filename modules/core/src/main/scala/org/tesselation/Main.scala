package org.tesselation

import java.security.KeyPair

import cats.effect._
import cats.effect.std.Supervisor

import org.tesselation.config.Config
import org.tesselation.config.types.KeyConfig
import org.tesselation.http.p2p.P2PClient
import org.tesselation.keytool.KeyStoreUtils
import org.tesselation.keytool.security.SecurityProvider
import org.tesselation.kryo.{KryoSerializer, coreKryoRegistrar}
import org.tesselation.modules._
import org.tesselation.resources.MkHttpServer.ServerName
import org.tesselation.resources.{AppResources, MkHttpServer}
import org.tesselation.schema.peer.PeerId

import org.typelevel.log4cats.slf4j.Slf4jLogger

object Main extends IOApp {

  implicit val logger = Slf4jLogger.getLogger[IO]

  override def run(args: List[String]): IO[ExitCode] =
    Config.load[IO].flatMap { cfg =>
      logger.info(s"Config loaded") >>
        logger.info(s"App environment: ${cfg.environment}") >>
        SecurityProvider.forAsync[IO].use { implicit securityProvider =>
          loadKeyPair(cfg.keyConfig).flatMap { keyPair =>
            KryoSerializer.forAsync[IO](coreKryoRegistrar).use { implicit kryoPool =>
              Supervisor[IO].use { _ =>
                (for {
                  res <- AppResources.make[IO](cfg)
                  nodeId = PeerId.fromPublic(keyPair.getPublic)
                  p2pClient = P2PClient.make[IO](res.client)
                  storages <- Resource.eval {
                    Storages.make[IO]
                  }
                  services <- Resource.eval {
                    Services.make[IO](cfg, nodeId, keyPair, storages)
                  }
                  programs <- Resource.eval { Programs.make[IO](storages, services, p2pClient, nodeId) }
                  api = HttpApi.make[IO](storages, services, programs, cfg.environment)
                  _ <- MkHttpServer[IO].newEmber(ServerName("public"), cfg.publicHttp, api.publicApp)
                  _ <- MkHttpServer[IO].newEmber(ServerName("p2p"), cfg.p2pHttp, api.p2pApp)
                  _ <- MkHttpServer[IO].newEmber(ServerName("cli"), cfg.cliHttp, api.cliApp)
                } yield ()).useForever
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
