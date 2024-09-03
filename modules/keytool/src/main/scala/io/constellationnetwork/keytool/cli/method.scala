package io.constellationnetwork.keytool.cli

import cats.syntax.contravariantSemigroupal._

import io.constellationnetwork.env.env._
import io.constellationnetwork.ext.decline.WithOpts
import io.constellationnetwork.keytool.cert.DistinguishedName

import com.monovore.decline.Opts
import io.estatico.newtype.ops._

object method {

  val distinguishedName = DistinguishedName(
    commonName = "constellationnetwork.io",
    organization = "Constellation Labs"
  )
  val certificateValidityDays: Long = 365 * 1000 // 1000 years of validity should be enough I guess

  sealed trait CliMethod

  case class GenerateWallet(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    distinguishedName: DistinguishedName = distinguishedName,
    certificateValidityDays: Long = certificateValidityDays
  ) extends CliMethod

  object GenerateWallet extends WithOpts[GenerateWallet] {

    val opts =
      Opts.subcommand("generate", "Generate wallet") {
        (StorePath.opts, KeyAlias.opts, Password.opts).mapN(GenerateWallet.apply(_, _, _))
      }
  }

  case class MigrateExistingKeyStoreToStorePassOnly(
    keyStore: StorePath,
    alias: KeyAlias,
    storepass: StorePass,
    keypass: KeyPass,
    distinguishedName: DistinguishedName = distinguishedName,
    certificateValidityDays: Long = certificateValidityDays
  ) extends CliMethod

  object MigrateExistingKeyStoreToStorePassOnly extends WithOpts[MigrateExistingKeyStoreToStorePassOnly] {

    val opts =
      Opts.subcommand("migrate", "Clone existing KeyStore and set storepass for both store and keypair") {
        (StorePath.opts, KeyAlias.opts, StorePass.opts, KeyPass.opts)
          .mapN(MigrateExistingKeyStoreToStorePassOnly.apply(_, _, _, _))
      }
  }

  case class ExportPrivateKeyHex(
    keyStore: StorePath,
    alias: KeyAlias,
    storepass: StorePass,
    keypass: KeyPass
  ) extends CliMethod

  object ExportPrivateKeyHex extends WithOpts[ExportPrivateKeyHex] {

    val opts = Opts.subcommand("export", "Exports PrivateKey in hexadecimal format") {
      (
        StorePath.opts,
        KeyAlias.opts,
        (StorePass.opts, KeyPass.opts).tupled
          .orElse(Password.opts.map(pw => (StorePass(pw.coerce), KeyPass(pw.coerce))))
      ).mapN {
        case (storepath, alias, (storepass, keypass)) => ExportPrivateKeyHex(storepath, alias, storepass, keypass)
      }
    }
  }

  val opts: Opts[CliMethod] =
    GenerateWallet.opts.orElse(MigrateExistingKeyStoreToStorePassOnly.opts).orElse(ExportPrivateKeyHex.opts)
}
