package org.tesselation.keytool.security

import java.security.spec.ECGenParameterSpec
import java.security.{KeyPair, KeyPairGenerator, PrivateKey}

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

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

  private def getKeyPairGenerator[F[_]: Async: SecurityProvider]: F[KeyPairGenerator] =
    for {
      ecSpec <- Async[F].delay { new ECGenParameterSpec(secp256k) }
      bcProvider = SecurityProvider[F].provider
      keyGen <- Async[F].delay { KeyPairGenerator.getInstance(ECDSA, bcProvider) }
      secureRandom <- SecureRandom.get[F]
      _ <- Async[F].delay { keyGen.initialize(ecSpec, secureRandom) }
    } yield keyGen

  def makeKeyPair[F[_]: Async: SecurityProvider]: F[KeyPair] =
    for {
      keyGen <- getKeyPairGenerator[F]
      keyPair <- Async[F].delay { keyGen.generateKeyPair() }
    } yield keyPair

  private val PublicKeyHexPrefix: String = "3056301006072a8648ce3d020106052b8104000a03420004"
  private val PublicKeyHexPrefixLength: Int = PublicKeyHexPrefix.length
  private val PrivateKeyHexPrefix: String =
    "30818d020100301006072a8648ce3d020106052b8104000a047630740201010420"
  private val PrivateKeyHexPrefixLength: Int = PrivateKeyHexPrefix.length
  private val secp256kHexIdentifier: String = "a00706052b8104000aa144034200"

  def bytes2hex(bytes: Array[Byte], sep: Option[String] = None): String =
    sep match {
      case None => bytes.map("%02x".format(_)).mkString
      case _    => bytes.map("%02x".format(_)).mkString(sep.get)
    }

  /**
    * Full hex has following format:
    * PRIVATEKEYHEXa00706052b8104000aa144034200PUBLICKEYHEX where a00706052b8104000aa144034200 stands for secp256k identifier
    */
  def privateKeyToFullHex(privateKey: PrivateKey): String = {
    val hex = bytes2hex(privateKey.getEncoded)
    hex.slice(PrivateKeyHexPrefixLength, hex.length)
  }

  def privateKeyToHex(privateKey: PrivateKey): String = {
    val fullHex = privateKeyToFullHex(privateKey)
    val privateHex = fullHex.split(secp256kHexIdentifier).head
    privateHex
  }
}
