package org.tessellation.security.key

import java.security.{PrivateKey, PublicKey}

import org.tessellation.schema.ID.Id
import org.tessellation.schema.address._
import org.tessellation.security.hex.Hex

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

    def toAddress: Address = Address.fromBytes(publicKey.getEncoded())

    def toId: Id = Id(toHex)

    def toHex: Hex = {
      val hex = Hex.fromBytes(publicKey.getEncoded).coerce
      hex.stripPrefix(PublicKeyHexPrefix).coerce[Hex]
    }
  }
}
