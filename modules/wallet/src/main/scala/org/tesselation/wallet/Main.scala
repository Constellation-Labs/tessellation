package org.tesselation.wallet
import java.security.KeyPair

import cats.effect._
import cats.effect.std.Console
import cats.syntax.all._

import org.tesselation.crypto.ops._
import org.tesselation.keytool.KeyStoreUtils
import org.tesselation.keytool.security.SecurityProvider
import org.tesselation.wallet.cli.env.EnvConfig
import org.tesselation.wallet.cli.method._

import com.monovore.decline._
import com.monovore.decline.effect._
import io.estatico.newtype.ops._
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Main
    extends CommandIOApp(
      name = "",
      header = "Constellation Wallet",
      version = "0.0.x"
    ) {
  implicit val logger = Slf4jLogger.getLogger[IO]

  override def main: Opts[IO[ExitCode]] =
    (cli.method.opts, cli.env.opts).mapN {
      case (method, envs) =>
        SecurityProvider.forAsync[IO].use { implicit sp =>
          loadKeyPair[IO](envs).flatMap { keyPair =>
            method match {
              case ShowAddress() =>
                showAddress[IO](keyPair)
                  .handleErrorWith(err => logger.error(err)(s"Error while showing address."))
                  .as(ExitCode.Success)
              case ShowId() =>
                showId[IO](keyPair)
                  .handleErrorWith(err => logger.error(err)(s"Error while showing id."))
                  .as(ExitCode.Success)
              case ShowPublicKey() =>
                showPublicKey[IO](keyPair)
                  .handleErrorWith(err => logger.error(err)(s"Error while showing id."))
                  .as(ExitCode.Success)
              case CreateTransaction(_, _, _, _, _) =>
                IO(ExitCode.Error)
            }
          }
        }
    }

  private def showAddress[F[_]: Console](keyPair: KeyPair): F[Unit] = Console[F].println(keyPair.getPublic.toHex)

  private def showId[F[_]: Console](keyPair: KeyPair): F[Unit] = Console[F].println(keyPair.getPublic.toId)

  private def showPublicKey[F[_]: Console](keyPair: KeyPair): F[Unit] = Console[F].println(keyPair.getPublic)

  private def loadKeyPair[F[_]: Async: SecurityProvider](cfg: EnvConfig): F[KeyPair] =
    KeyStoreUtils
      .readKeyPairFromStore[F](
        cfg.keystore.toString,
        cfg.keyalias.coerce.value,
        cfg.storepass.coerce.value.toCharArray,
        cfg.keypass.coerce.value.toCharArray
      )
}
