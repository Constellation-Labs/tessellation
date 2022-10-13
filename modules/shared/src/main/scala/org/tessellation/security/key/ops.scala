package org.tessellation.security.key

import java.security.{PrivateKey, PublicKey}

import org.tessellation.schema.address._
import org.tessellation.schema.hex._
import org.tessellation.schema.id.Id
import org.tessellation.security._
import org.tessellation.security.hash.Hash

import com.google.common.hash.HashCode
import eu.timepit.refined.auto._
import eu.timepit.refined.refineV
import io.estatico.newtype.ops._

object ops {

  implicit class PrivateKeyOps(privateKey: PrivateKey) {
    def toHex: HexString =
      refineV[HexStringSpec].unsafeFrom(
        privateKey.getEncoded.toHexString.value.stripPrefix(PrivateKeyHexPrefix).split(secp256kHexIdentifier).head
      )
  }

  implicit class PublicKeyOps(publicKey: PublicKey) {

    private def toHashCodeBase58String: String = {
      val hashCode = HashCode.fromString(Hash.fromBytes(publicKey.getEncoded).coerce)
      val bytes = hashCode.asBytes().toIndexedSeq
      Base58.encode(bytes)
    }

    def toAddress: Address = {
      val hash = toHashCodeBase58String
      val end = hash.slice(hash.length - 36, hash.length)
      val validInt = end.filter(Character.isDigit)
      val ints = validInt.map(_.toString.toInt)
      val sum = ints.sum
      val par = sum % 9
      val res2: DAGAddress = refineV[DAGAddressRefined].unsafeFrom(s"DAG$par$end")
      Address(res2)
    }

    def toId: Id =
      Id(refineV[HexString128Spec].unsafeFrom(publicKey.getEncoded.toHexString.value.stripPrefix(PublicKeyHexPrefix)))
  }
}
