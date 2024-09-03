package io.constellationnetwork.wallet.cli

import cats.syntax.contravariantSemigroupal._

import io.constellationnetwork.env.env._

import io.estatico.newtype.ops._

object env {
  case class EnvConfig(
    keystore: StorePath,
    storepass: StorePass,
    keypass: KeyPass,
    keyalias: KeyAlias
  )

  val opts = (
    StorePath.opts,
    (StorePass.opts, KeyPass.opts).tupled.orElse(Password.opts.map(pw => (StorePass(pw.coerce), KeyPass(pw.coerce)))),
    KeyAlias.opts
  ).mapN {
    case (storepath, (storepass, keypass), alias) => EnvConfig(storepath, storepass, keypass, alias)
  }
}
