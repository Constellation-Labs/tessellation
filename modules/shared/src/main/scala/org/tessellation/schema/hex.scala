package org.tessellation.schema

import java.nio.charset.StandardCharsets
import java.security.spec.X509EncodedKeySpec
import java.security.{KeyFactory, PublicKey}

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.security.SecurityProvider
import org.tessellation.security.key.{ECDSA, PublicKeyHexPrefix}

import eu.timepit.refined.api.Refined
import eu.timepit.refined.boolean.And
import eu.timepit.refined.collection.Size
import eu.timepit.refined.generic.Equal
import eu.timepit.refined.numeric.Even
import eu.timepit.refined.refineV
import eu.timepit.refined.scalacheck.arbitraryRefType
import eu.timepit.refined.string.MatchesRegex
import org.scalacheck.{Arbitrary, Gen}
import shapeless.Witness

object hex {

  type HexStringSpec = MatchesRegex["^[0-9a-f]*$"] And Size[Even]
  type HexString = String Refined HexStringSpec

  type HexStringOfSizeSpec[N <: Int] = HexStringSpec And Size[Equal[N]]
  type HexStringOfSize[N <: Int] = String Refined HexStringOfSizeSpec[N]

  type HexString128Spec = HexStringOfSizeSpec[128]
  type HexString128 = String Refined HexString128Spec

  type HexString64Spec = HexStringOfSizeSpec[64]
  type HexString64 = String Refined HexString64Spec

  implicit class HexStringOps(value: HexString) {
    def toBytes: Array[Byte] = {
      val plain = value.value
      val result = new Array[Byte](plain.length / 2)

      var i = 0
      while (i < plain.length) {
        result(i / 2) = (
          (Character.digit(plain.charAt(i), 16) << 4) + (Character.digit(plain.charAt(i + 1), 16))
        ).toByte
        i = i + 2
      }

      result
    }
  }

  implicit class HexStringOfSizeOps[N <: Int](value: HexStringOfSize[N]) {
    def toBytes: Array[Byte] = refineV[HexStringSpec].unsafeFrom(value.value).toBytes
  }

  implicit def arbitraryHexOfSize[N <: Int](implicit ws: Witness.Aux[N]): Arbitrary[HexStringOfSize[N]] = arbitraryRefType(
    Gen.stringOfN(ws.value, Gen.hexChar.map(_.toLower))
  )

  implicit def arbitraryHexString: Arbitrary[HexString] = arbitraryRefType(
    Gen.choose(0, 1000).flatMap(n => Gen.stringOfN(n * 2, Gen.hexChar.map(_.toLower)))
  )

  implicit class ByteArrayOps(value: Array[Byte]) {
    val hexDigits: Array[Byte] = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    def toHexString: HexString = {
      val result = new Array[Byte](value.length * 2)

      var i = 0
      while (i < value.length) {
        val v = value(i) & 0xff
        result(i * 2) = hexDigits(v >>> 4)
        result(i * 2 + 1) = hexDigits(v & 0x0f)
        i = i + 1
      }

      refineV[HexStringSpec].unsafeFrom(new String(result, StandardCharsets.UTF_8))
    }
  }

  implicit class Hex128Ops(value: HexString128) {

    def toPublicKey[F[_]: Async: SecurityProvider]: F[PublicKey] =
      for {
        _ <- Async[F].unit
        encodedBytes = PublicKeyHexPrefix.toBytes.concat(value.toBytes)
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
  }

}
