package org.tesselation.crypto

import java.security.{PrivateKey, PublicKey}

import org.tesselation.crypto.hash.Hash
import org.tesselation.crypto.hex.Hex
import org.tesselation.keytool.Base58
import org.tesselation.keytool.security._
import org.tesselation.schema.ID.Id
import org.tesselation.schema.address.{Address, DAGAddress}

import com.google.common.hash.HashCode
import eu.timepit.refined.auto._
import io.estatico.newtype.ops._

package object ops {

  implicit class PrivateKeyOps(privateKey: PrivateKey) {

    def toFullHex: Hex = {
      val hex: String = Hex.fromBytes(privateKey.getEncoded).coerce
      hex.stripPrefix(PrivateKeyHexPrefix).coerce[Hex]
    }

    def toHex: Hex = {
      val fullHex = toFullHex.coerce
      fullHex.split(secp256kHexIdentifier).head.coerce[Hex]
    }
  }

  implicit class PublicKeyOps(publicKey: PublicKey) {

    def toHash: Hash = {
      val hashCode = HashCode.fromString(Hash.fromBytes(publicKey.getEncoded).coerce)
      val bytes = hashCode.asBytes().toIndexedSeq
      val encoded = Base58.encode(bytes)
      Hash(encoded)
    }

    def toAddress: Address = {
      val hash = toHash.coerce
      val end = hash.slice(hash.length - 36, hash.length)
      val validInt = end.filter { Character.isDigit }
      val ints = validInt.map { _.toString.toInt }
      val sum = ints.sum
      val par = sum % 9
      val res2 = s"DAG$par$end".asInstanceOf[DAGAddress] // We already know that it is valid so we can cast
      Address(res2)
    }

    def toId: Id = Id(toHex.coerce)

    def toHex: Hex = {
      val hex = Hex.fromBytes(publicKey.getEncoded).coerce
      hex.stripPrefix(PublicKeyHexPrefix).coerce[Hex]
    }
  }
}
