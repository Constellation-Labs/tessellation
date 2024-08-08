package org.tessellation.json

import cats.effect.{IO, Resource}

import scala.collection.immutable.SortedMap

import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshot, CurrencySnapshotInfo}
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.security._
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed
import org.tessellation.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import weaver.MutableIOSuite

object JsonBrotliBinarySerializerSuite extends MutableIOSuite {

  type Res = (Hasher[IO], JsonBrotliBinarySerializer[IO])

  override def sharedResource: Resource[IO, Res] =
    KryoSerializer
      .forAsync[IO](sharedKryoRegistrar)
      .flatMap { implicit res =>
        JsonSerializer.forSync[IO].asResource.map { implicit json =>
          Hasher.forJson[IO]
        }
      }
      .flatMap { kp =>
        JsonBrotliBinarySerializer.forSync[IO].asResource.map((kp, _))
      }

  test("should deserialize properly serialized object") {
    case (hasher, serializer) =>
      implicit val h = hasher

      for {
        signedSnapshot <- JsonBinarySerializerSuite
          .currencyIncrementalSnapshot[IO](Hash.empty, CurrencySnapshotInfo(SortedMap.empty, SortedMap.empty, None, None, None, None))
        serialized <- serializer.serialize(signedSnapshot)
        deserialized <- serializer.deserialize[Signed[CurrencyIncrementalSnapshot]](serialized)
      } yield expect.same(Right(signedSnapshot), deserialized)
  }

  test("should not deserialize different serialized object") {
    case (hasher, serializer) =>
      implicit val h = hasher

      for {
        signedSnapshot <- JsonBinarySerializerSuite
          .currencyIncrementalSnapshot[IO](Hash.empty, CurrencySnapshotInfo(SortedMap.empty, SortedMap.empty, None, None, None, None))
        serialized <- serializer.serialize(signedSnapshot)
        deserialized <- serializer.deserialize[CurrencySnapshot](serialized)
      } yield expect.same(true, deserialized.isLeft)
  }

}
