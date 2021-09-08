package org.tesselation.keytool.security

import cats.effect.Async
import cats.syntax.functor._
import cats.syntax.flatMap._
import org.bouncycastle.jce.provider.BouncyCastleProvider

import java.security.spec.ECGenParameterSpec
import java.security.{KeyPair, KeyPairGenerator}

/**
  * Need to compare this to:
  * https://github.com/bitcoinj/bitcoinj/blob/master/core/src/main/java/org/bitcoinj/core/ECKey.java
  *
  * The implementation here is a lot simpler and from stackoverflow post linked below
  * but has differences. Same library dependency but needs to be checked
  *
  * BitcoinJ is stuck on Java 6 for some things, so if we use it, probably better to pull
  * out any relevant code rather than using as a dependency.
  *
  * Based on: http://www.bouncycastle.org/wiki/display/JA1/Elliptic+Curve+Key+Pair+Generation+and+Key+Factories
  * I think most of the BitcoinJ extra code is just overhead for customization.
  * Look at the `From Named Curves` section of above citation. Pretty consistent with stackoverflow code
  * and below implementation.
  *
  * Need to review: http://www.bouncycastle.org/wiki/display/JA1/Using+the+Bouncy+Castle+Provider%27s+ImplicitlyCA+Facility
  * for security policy implications.
  *
  */

object KeyProvider {
  private val ECDSA = "ECDsA"
  private val secp256k = "secp256k1"

  def getKeyPairGenerator[F[_]: Async](securityProvider: BouncyCastleProvider): F[KeyPairGenerator] =
    for {
      ecSpec <- Async[F].delay { new ECGenParameterSpec(secp256k) }
      keyGen <- Async[F].delay { KeyPairGenerator.getInstance(ECDSA, securityProvider) }
      secureRandom <- SecureRandom.get[F]
      _ <- Async[F].delay { keyGen.initialize(ecSpec, secureRandom) }
    } yield keyGen

  def makeKeyPair[F[_]: Async](securityProvider: BouncyCastleProvider): F[KeyPair] =
    for {
      keyGen <- getKeyPairGenerator[F](securityProvider)
      keyPair <- Async[F].delay { keyGen.generateKeyPair() }
    } yield keyPair
}
