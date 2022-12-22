package org.tessellation.schema.security.key

import java.security.{PrivateKey, PublicKey}

import org.tessellation.schema.ID.Id
import org.tessellation.schema.address._
import org.tessellation.schema.security._
import org.tessellation.schema.security.hash.Hash
import org.tessellation.schema.security.hex.Hex

import com.google.common.hash.HashCode
import eu.timepit.refined.refineV
import io.estatico.newtype.ops._

object ops {

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
      val validInt = end.filter(Character.isDigit)
      val ints = validInt.map(_.toString.toInt)
      val sum = ints.sum
      val par = sum % 9
      val res2: DAGAddress = refineV[DAGAddressRefined].unsafeFrom(s"DAG$par$end")
      Address(res2)
    }

    def toId: Id = Id.apply(toHex)

    def toHex: Hex = {
      val hex = Hex.fromBytes(publicKey.getEncoded).coerce
      hex.stripPrefix(PublicKeyHexPrefix).coerce[Hex]
    }
  }
}
