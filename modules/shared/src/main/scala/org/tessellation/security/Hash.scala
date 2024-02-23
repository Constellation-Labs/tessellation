package org.tessellation.security

import java.nio.charset.StandardCharsets

import cats.Show
import cats.effect.kernel.Sync
import cats.syntax.all._

import scala.annotation.nowarn

import org.tessellation.ext.derevo.ordering
import org.tessellation.json.JsonHashSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.security.hash.Hash

import com.google.common.hash.{HashCode, Hashing}
import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.circe.Encoder
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

    def hashCodeFromBytes(bytes: Array[Byte]): HashCode =
      Hashing.sha256().hashBytes(bytes)

    def fromBytes(bytes: Array[Byte]): Hash =
      Hash(hashCodeFromBytes(bytes).toString)

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

trait Hasher[F[_]] {
  def hash[A: Encoder](data: A): F[Hash]
}

object Hasher {

  def apply[F[_]: Hasher]: Hasher[F] = implicitly

  def forSync[F[_]: Sync: KryoSerializer: JsonHashSerializer]: Hasher[F] = new Hasher[F] {

    @nowarn
    def hashJson[A: Encoder](data: A): F[Hash] =
      (data match {
        case d: Encodable[_] =>
          JsonHashSerializer[F].serialize(d.toEncode)(d.jsonEncoder)
        case _ =>
          JsonHashSerializer[F].serialize[A](data)
      }).map(Hash.fromBytes)

    def hashKryo[A](data: A): F[Hash] =
      KryoSerializer[F]
        .serialize(data match {
          case d: Encodable[_] => d.toEncode
          case _               => data
        })
        .map(Hash.fromBytes)
        .liftTo[F]

    // NOTE: For backward compatibility - KryoSerializer is the hashing driver for now
    def hash[A: Encoder](data: A): F[Hash] = hashKryo(data)

  }
}
