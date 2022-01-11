package org.tessellation.sdk.app

import java.security.KeyPair

import cats.effect._
import cats.effect.std.Random
import cats.syntax.show._

import org.tessellation.cli.env.{KeyAlias, Password, StorePath}
import org.tessellation.ext.cats.effect._
import org.tessellation.keytool.KeyStoreUtils
import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.cli.CliMethod
import org.tessellation.sdk.infrastructure.logs.LoggerConfigurator
import org.tessellation.sdk.kryo.sdkKryoRegistrar
import org.tessellation.sdk.resources.SdkResources
import org.tessellation.security.SecurityProvider

import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import org.typelevel.log4cats.slf4j.Slf4jLogger

abstract class TessellationIOApp[A <: CliMethod](
  name: String,
  header: String,
  helpFlag: Boolean = true,
  version: String = ""
) extends CommandIOApp(
      name,
      header,
      helpFlag,
      version
    ) {

  /**
    * Command-line opts
    */
  def opts: Opts[A]

  /**
    * Kryo registration is required for (de)serialization.
    */
  val kryoRegistrar: Map[Class[_], Int]

  protected implicit val logger = Slf4jLogger.getLogger[IO]

  def run(cfg: A, sdk: SDK[IO]): Resource[IO, Unit]

  override final def main: Opts[IO[ExitCode]] =
    opts.map { method =>
      val cfg = method.sdkConfig

      val keyStore = method.keyStore
      val alias = method.alias
      val password = method.password

      val registrar = kryoRegistrar ++ sdkKryoRegistrar

      LoggerConfigurator.configureLogger[IO](cfg.environment) >>
        logger.info(s"App environment: ${cfg.environment}") >>
        Random.scalaUtilRandom[IO].flatMap { _random =>
          SecurityProvider.forAsync[IO].use { implicit _securityProvider =>
            loadKeyPair[IO](keyStore, alias, password).flatMap { _keyPair =>
              KryoSerializer.forAsync[IO](registrar).use { _kryoPool =>
                (for {

                  res <- SdkResources.make[IO](cfg)

                  sdk = new SDK[IO] {
                    val random = _random
                    val securityProvider = _securityProvider
                    val kryoPool = _kryoPool

                    val keyPair = _keyPair

                    val sdkResources = res
                  }

                  _ <- logger.info(s"Self peerId: ${sdk.nodeId.show}").asResource

                  _ <- run(method, sdk)
                } yield ()).useForever
              }
            }
          }
        }
    }

  private def loadKeyPair[F[_]: Async: SecurityProvider](
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password
  ): F[KeyPair] =
    KeyStoreUtils
      .readKeyPairFromStore[F](
        keyStore.value.toString,
        alias.value.value,
        password.value.value.toCharArray,
        password.value.value.toCharArray
      )

}
