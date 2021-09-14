package org.tesselation.keytool

import java.security.KeyStore

import cats.effect._
import cats.syntax.flatMap._

import org.tesselation.keytool.cli.config.CliMethod
import org.tesselation.keytool.cli.parser
import org.tesselation.keytool.config.KeytoolConfig
import org.tesselation.keytool.config.types.AppConfig
import org.tesselation.keytool.security.SecurityProvider

import org.typelevel.log4cats.slf4j.Slf4jLogger

object Main extends IOApp {
  implicit val logger = Slf4jLogger.getLogger[IO]

  override def run(args: List[String]): IO[ExitCode] =
    KeytoolConfig
      .load[IO]
      .flatMap { cfg =>
        parser
          .parse[IO](args)
          .flatMap { cli =>
            SecurityProvider.forAsync[IO].use { implicit securityProvider =>
              cli.method match {
                case CliMethod.GenerateWallet =>
                  generateKeyStoreWithKeyPair[IO](cfg).flatTap { _ =>
                    logger.info(s"KeyPair has been created at: ${cfg.keystore}")
                  }.handleErrorWith(err => logger.error(err)(s"Error while creating a keystore.")).as(ExitCode.Success)
                case CliMethod.MigrateExistingKeyStoreToStorePassOnly =>
                  migrateKeyStoreToSinglePassword[IO](cfg).flatTap { _ =>
                    logger.info(s"KeyPair has been migrated at: ${cfg.keystore}")
                  }.handleErrorWith(err => logger.error(err)(s"Error while creating a keystore.")).as(ExitCode.Success)
                case CliMethod.ExportPrivateKeyHex =>
                  exportPrivateKeyAsHex[IO](cfg).flatTap { hex =>
                    logger.info(s"PrivateKey in hex: ${hex}")
                  }.handleErrorWith(err => logger.error(err)(s"Error while creating a keystore.")).as(ExitCode.Success)
                case _ => IO(ExitCode.Error)
              }
            }
          }
          .handleErrorWith(_ => IO(ExitCode.Error))
      }

  private def generateKeyStoreWithKeyPair[F[_]: Async: SecurityProvider](cfg: AppConfig): F[KeyStore] =
    KeyStoreUtils
      .generateKeyPairToStorePath(
        path = cfg.keystore,
        alias = cfg.keyalias.value,
        storePassword = cfg.storepass.value.toCharArray,
        keyPassword = cfg.keypass.value.toCharArray,
        distinguishedName = cfg.distinguishedName,
        certificateValidity = cfg.certificateValidity
      )

  private def migrateKeyStoreToSinglePassword[F[_]: Async](cfg: AppConfig): F[KeyStore] =
    KeyStoreUtils.migrateKeyStoreToSinglePassword(
      path = cfg.keystore,
      alias = cfg.keyalias.value,
      storePassword = cfg.storepass.value.toCharArray,
      keyPassword = cfg.keypass.value.toCharArray,
      distinguishedName = cfg.distinguishedName,
      certificateValidity = cfg.certificateValidity
    )

  private def exportPrivateKeyAsHex[F[_]: Async](cfg: AppConfig): F[String] =
    KeyStoreUtils.exportPrivateKeyAsHex(
      path = cfg.keystore,
      alias = cfg.keyalias.value,
      storePassword = cfg.storepass.value.toCharArray,
      keyPassword = cfg.keypass.value.toCharArray
    )
}
