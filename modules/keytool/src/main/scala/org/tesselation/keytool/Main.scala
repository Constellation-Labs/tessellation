package org.tesselation.keytool

import cats.effect.{ExitCode, IO, IOApp}

import org.tesselation.keytool.config.KeytoolConfig
import org.tesselation.keytool.security.{KeyProvider, SecurityProvider}

import org.typelevel.log4cats.slf4j.Slf4jLogger

object Main extends IOApp {
  implicit val logger = Slf4jLogger.getLogger[IO]

  override def run(args: List[String]): IO[ExitCode] =
    KeytoolConfig
      .load[IO]
      .flatMap { cfg =>
        logger.info(s"Config loaded") >>
          SecurityProvider.forAsync[IO].use { implicit securityProvider =>
            KeyProvider.makeKeyPair[IO] >>
              KeyProvider.makeKeyPair[IO] >>
              KeyProvider.makeKeyPair[IO].flatMap { keypair =>
                logger.info(s"PublicKey=${keypair.getPublic.toString}")
              }
          }

      }
      .as(ExitCode.Success)
}
