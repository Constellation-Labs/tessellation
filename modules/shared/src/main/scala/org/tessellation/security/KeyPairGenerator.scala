package org.tessellation.security

import java.security.spec.ECGenParameterSpec
import java.security.{KeyPair, KeyPairGenerator => JKeyPairGenerator}

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.security.key.{ECDSA, secp256k}
import org.tessellation.security.{SecureRandom, SecurityProvider}

/** Need to compare this to: https://github.com/bitcoinj/bitcoinj/blob/master/core/src/main/java/org/bitcoinj/core/ECKey.java
  *
  * The implementation here is a lot simpler and from stackoverflow post linked below but has differences. Same library dependency but needs
  * to be checked
  *
  * BitcoinJ is stuck on Java 6 for some things, so if we use it, probably better to pull out any relevant code rather than using as a
  * dependency.
  *
  * Based on: http://www.bouncycastle.org/wiki/display/JA1/Elliptic+Curve+Key+Pair+Generation+and+Key+Factories I think most of the BitcoinJ
  * extra code is just overhead for customization. Look at the `From Named Curves` section of above citation. Pretty consistent with
  * stackoverflow code and below implementation.
  *
  * Need to review: http://www.bouncycastle.org/wiki/display/JA1/Using+the+Bouncy+Castle+Provider%27s+ImplicitlyCA+Facility for security
  * policy implications.
  */

object KeyPairGenerator {

  private def getKeyPairGenerator[F[_]: Async: SecurityProvider]: F[JKeyPairGenerator] =
    for {
      ecSpec <- Async[F].delay(new ECGenParameterSpec(secp256k))
      bcProvider = SecurityProvider[F].provider
      keyGen <- Async[F].delay(JKeyPairGenerator.getInstance(ECDSA, bcProvider))
      secureRandom <- SecureRandom.get[F]
      _ <- Async[F].delay(keyGen.initialize(ecSpec, secureRandom))
    } yield keyGen

  def makeKeyPair[F[_]: Async: SecurityProvider]: F[KeyPair] =
    for {
      keyGen <- getKeyPairGenerator[F]
      keyPair <- Async[F].delay(keyGen.generateKeyPair())
    } yield keyPair

}
