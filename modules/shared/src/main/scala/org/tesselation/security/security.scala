package org.tesselation

import java.security.spec.X509EncodedKeySpec
import java.security.{KeyFactory, PrivateKey, PublicKey}

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tesselation.security.key.ECDSA

package object security {
  val PublicKeyHexPrefix: String = "3056301006072a8648ce3d020106052b8104000a03420004"

  val PrivateKeyHexPrefix: String =
    "30818d020100301006072a8648ce3d020106052b8104000a047630740201010420"
  val secp256kHexIdentifier: String = "a00706052b8104000aa144034200"

  def hex2bytes(hex: String): Array[Byte] =
    if (hex.contains(" ")) {
      hex.split(" ").map(Integer.parseInt(_, 16).toByte)
    } else if (hex.contains("-")) {
      hex.split("-").map(Integer.parseInt(_, 16).toByte)
    } else {
      hex.sliding(2, 2).toArray.map(Integer.parseInt(_, 16).toByte)
    }

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
    hex.stripPrefix(PrivateKeyHexPrefix)
  }

  def privateKeyToHex(privateKey: PrivateKey): String = {
    val fullHex = privateKeyToFullHex(privateKey)
    val privateHex = fullHex.split(secp256kHexIdentifier).head
    privateHex
  }

  def publicKeyToHex(publicKey: PublicKey): String = {
    val hex = bytes2hex(publicKey.getEncoded)
    hex.stripPrefix(PublicKeyHexPrefix)
  }

  def bytesToPublicKey[F[_]: Async: SecurityProvider](encodedBytes: Array[Byte]): F[PublicKey] =
    for {
      spec <- Async[F].delay {
        new X509EncodedKeySpec(encodedBytes)
      }
      kf <- Async[F].delay {
        KeyFactory.getInstance(ECDSA, SecurityProvider[F].provider)
      }
      pk <- Async[F].delay {
        kf.generatePublic(spec)
      }
    } yield pk

  def hexToPublicKey[F[_]: Async: SecurityProvider](hex: String): F[PublicKey] =
    bytesToPublicKey[F](hex2bytes(PublicKeyHexPrefix + hex))

}
