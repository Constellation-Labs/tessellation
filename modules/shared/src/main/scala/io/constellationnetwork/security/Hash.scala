package io.constellationnetwork.security

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import cats.{Eq, Show}

import io.constellationnetwork.ext.derevo.ordering

import com.google.common.hash.{HashCode, Hashing}
import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops._
import org.scalacheck.{Arbitrary, Gen}

object hash {

  @derive(encoder, decoder, ordering, order, show)
  @newtype
  case class Hash(value: String) {
    def getBytes: Array[Byte] = value.getBytes(StandardCharsets.UTF_8)
  }

  object Hash {
    private val hexDigits = "0123456789abcdef".toCharArray
    private val sha256 = MessageDigest.getInstance("SHA-256")

    case class Sha256Digest(private val bytes: Array[Byte]) {
      def toByteArray: Array[Byte] = Array.copyOf(bytes, bytes.length)

      def toHexString: String =
        bytes
          .foldLeft(new StringBuilder(bytes.length * 2)) { (sb, b) =>
            sb.append(hexDigits((b >> 4) & 0xf)).append(hexDigits(b & 0xf))
          }
          .toString

      def toIndexedSeq: IndexedSeq[Byte] = bytes.toIndexedSeq
    }

    object Sha256Digest {
      implicit val show: Show[Sha256Digest] = Show.show(s => s"Sha256Digest(${s.toHexString})")
      implicit val eq: Eq[Sha256Digest] = Eq.instance((a, b) => a.bytes.sameElements(b.bytes))
    }

    @deprecated("Use sha256DigestFromBytes() instead", since = "3.0")
    def hashCodeFromBytes(bytes: Array[Byte]): HashCode =
      Hashing.sha256().hashBytes(bytes)

    def sha256DigestFromBytes(bytes: Array[Byte]): Sha256Digest = {
      val md = sha256.clone().asInstanceOf[MessageDigest]
      md.update(bytes)
      Sha256Digest(md.digest())
    }

    def fromBytes(bytes: Array[Byte]): Hash =
      Hash(sha256DigestFromBytes(bytes).toHexString)

    def empty: Hash = Hash(s"%064d".format(0))

    implicit val arbitrary: Arbitrary[Hash] = Arbitrary(Gen.stringOfN(64, Gen.hexChar).map(Hash(_)))

    val shortShow: Show[Hash] = Show.show[Hash](h => s"Hash(${h.value.take(8)})")
  }

  @derive(encoder, decoder, ordering, order, show)
  @newtype
  case class ProofsHash(value: String)

  object ProofsHash {
    implicit val arbitrary: Arbitrary[ProofsHash] = Arbitrary(
      Arbitrary.arbitrary[Hash].map(h => ProofsHash(h.coerce[String]))
    )
  }

}
