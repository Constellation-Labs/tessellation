package io.constellationnetwork.sdk
package keytool

import java.security.KeyPair

import cats.effect.kernel.Async

import io.constellationnetwork.security.{KeyPairGenerator, SecurityProvider}

/** Provides utility functions for keypair.
  */
package object keypair {

  /** Generates a cryptographic key pair.
    *
    * @tparam F
    *   The effect type, which must have an `Async` and `SecurityProvider` instance.
    * @return
    *   An `F[KeyPair]` representing the computation that yields the key pair.
    */
  def generate[F[_]: Async: SecurityProvider]: F[KeyPair] =
    KeyPairGenerator.makeKeyPair[F]
}
