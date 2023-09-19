package org.tessellation.json

import cats.effect.{IO, Resource}

import scala.collection.immutable.SortedMap

import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshot, CurrencySnapshotInfo}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed
import org.tessellation.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import weaver.MutableIOSuite

object JsonGzipBinarySerializerSuite extends MutableIOSuite {

  type Res = (KryoSerializer[IO], JsonGzipBinarySerializer[IO])

  override def sharedResource: Resource[IO, Res] =
    KryoSerializer.forAsync[IO](sharedKryoRegistrar).map { kp =>
      (kp, JsonGzipBinarySerializer.make[IO]())
    }

  test("should deserialize properly serialized object") {
    case (kryo, serializer) =>
      implicit val kp = kryo

      for {
        signedSnapshot <- JsonBinarySerializerSuite
          .currencyIncrementalSnapshot[IO](Hash.empty, CurrencySnapshotInfo(SortedMap.empty, SortedMap.empty))
        serialized <- serializer.serialize(signedSnapshot)
        deserialized <- serializer.deserialize[Signed[CurrencyIncrementalSnapshot]](serialized)
      } yield expect.same(Right(signedSnapshot), deserialized)
  }

  test("should not deserialize different serialized object") {
    case (kryo, serializer) =>
      implicit val kp = kryo

      for {
        signedSnapshot <- JsonBinarySerializerSuite
          .currencyIncrementalSnapshot[IO](Hash.empty, CurrencySnapshotInfo(SortedMap.empty, SortedMap.empty))
        serialized <- serializer.serialize(signedSnapshot)
        deserialized <- serializer.deserialize[CurrencySnapshot](serialized)
      } yield expect.same(true, deserialized.isLeft)
  }
}
