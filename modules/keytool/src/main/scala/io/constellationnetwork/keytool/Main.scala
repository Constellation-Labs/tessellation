package io.constellationnetwork.keytool

import java.security.KeyStore

import cats.effect.{Async, ExitCode, IO}
import cats.syntax.traverse._

import io.constellationnetwork.BuildInfo
import io.constellationnetwork.env.env._
import io.constellationnetwork.keytool.cert.DistinguishedName
import io.constellationnetwork.keytool.cli.method._
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.hex.Hex

import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import io.estatico.newtype.ops._
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Main
    extends CommandIOApp(
      name = "",
      header = "Constellation Keytool",
      version = BuildInfo.version
    ) {
  implicit val logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

  override def main: Opts[IO[ExitCode]] =
    cli.method.opts.map { method =>
      SecurityProvider.forAsync[IO].use { implicit sp =>
        method match {
          case GenerateWallet(keyStore, alias, password, distinguishedName, certificateValidityDays) =>
            generateKeyStoreWithKeyPair[IO](keyStore, alias, password, distinguishedName, certificateValidityDays).void
              .handleErrorWith(err => logger.error(err)(s"Error while generating a keystore."))
              .as(ExitCode.Success)
          case GenerateMultipleWallets(baseKeyStorePath, baseAlias, password, count, distinguishedName, certificateValidityDays) =>
            generateMultipleKeyStores[IO](baseKeyStorePath, baseAlias, password, count, distinguishedName, certificateValidityDays).void
              .handleErrorWith(err => logger.error(err)(s"Error while generating multiple keystores."))
              .as(ExitCode.Success)
          case MigrateExistingKeyStoreToStorePassOnly(
                keyStore,
                alias,
                storepass,
                keypass,
                distinguishedName,
                certificateValidityDays
              ) =>
            migrateKeyStoreToSinglePassword[IO](
              keyStore,
              alias,
              storepass,
              keypass,
              distinguishedName,
              certificateValidityDays
            ).void
              .handleErrorWith(err => logger.error(err)(s"Error while migrating the keystore."))
              .as(ExitCode.Success)
          case ExportPrivateKeyHex(keyStore, alias, storepass, keypass) =>
            exportPrivateKeyAsHex[IO](keyStore, alias, storepass, keypass).void
              .handleErrorWith(err => logger.error(err)(s"Error while exporting private key as hex."))
              .as(ExitCode.Success)
        }
      }
    }

  private def generateKeyStoreWithKeyPair[F[_]: Async: SecurityProvider](
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    distinguishedName: DistinguishedName,
    certificateValidityDays: Long
  ): F[KeyStore] =
    KeyStoreUtils
      .generateKeyPairToStore(
        path = keyStore.coerce.toString,
        alias = alias.coerce.value,
        password = password.coerce.value.toCharArray,
        distinguishedName = distinguishedName,
        certificateValidityDays = certificateValidityDays
      )

  private def generateMultipleKeyStores[F[_]: Async: SecurityProvider](
    baseKeyStorePath: StorePath,
    baseAlias: KeyAlias,
    password: Password,
    count: Int,
    distinguishedName: DistinguishedName,
    certificateValidityDays: Long
  ): F[List[KeyStore]] =
    (1 to count).toList.traverse { i =>
      val keyStorePath = s"${baseKeyStorePath.coerce.toString}_$i.p12"
      val aliasStr = s"${baseAlias.coerce.value}_$i"

      KeyStoreUtils.generateKeyPairToStore(
        path = keyStorePath,
        alias = aliasStr,
        password = password.coerce.value.toCharArray,
        distinguishedName = distinguishedName,
        certificateValidityDays = certificateValidityDays
      )
    }

  private def migrateKeyStoreToSinglePassword[F[_]: Async: SecurityProvider](
    keyStore: StorePath,
    alias: KeyAlias,
    storePass: StorePass,
    keyPass: KeyPass,
    distinguishedName: DistinguishedName,
    certificateValidityDays: Long
  ): F[KeyStore] =
    KeyStoreUtils.migrateKeyStoreToSinglePassword(
      path = keyStore.coerce.toString,
      alias = alias.coerce.value,
      storePassword = storePass.coerce.value.toCharArray,
      keyPassword = keyPass.coerce.value.toCharArray,
      distinguishedName = distinguishedName,
      certificateValidityDays = certificateValidityDays
    )

  private def exportPrivateKeyAsHex[F[_]: Async: SecurityProvider](
    keyStore: StorePath,
    alias: KeyAlias,
    storePass: StorePass,
    keyPass: KeyPass
  ): F[Hex] =
    KeyStoreUtils.exportPrivateKeyAsHex(
      path = keyStore.coerce.toString,
      alias = alias.coerce.value,
      storePassword = storePass.coerce.value.toCharArray,
      keyPassword = keyPass.coerce.value.toCharArray
    )
}
