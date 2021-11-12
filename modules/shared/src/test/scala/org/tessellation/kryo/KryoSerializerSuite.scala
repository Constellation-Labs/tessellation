package org.tessellation.kryo

import cats.effect.IO
import cats.syntax.all._

import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object KryoSerializerSuite extends SimpleIOSuite with Checkers {

  test("v1 bytes should deserialize successfully by v2 serializer") {
    val v1 = NoChangesV1(amount = 15, address = "anyAddress")
    val migration = Migration { in: NoChangesV1 =>
      BreakingChangesClassV2(in.amount, "anyRemark")
    }

    val serializerV1 = KryoSerializer.forAsync[IO](Map(classOf[NoChangesV1] -> 100))
    val serializerV2 =
      KryoSerializer
        .forAsync[IO](Map(classOf[NoChangesV1] -> 100, classOf[BreakingChangesClassV2] -> 101), List(migration))

    for {
      bytes <- serializerV1.use { implicit kryo =>
        kryo.serialize(v1).liftTo[IO]
      }
      obj <- serializerV2.use { implicit kryo =>
        kryo.deserialize[BreakingChangesClassV2](bytes).liftTo[IO]
      }
      expectedV2 = BreakingChangesClassV2(amount = 15, remark = "anyRemark")
    } yield expect.same(obj, expectedV2)
  }

  test("v2 bytes should deserialize successfully by v1 serializer") {
    val v2 = NonBreakingChangesV2(amount = 15, address = "anyAddress", remark = "remark")

    val serializerV1 = KryoSerializer.forAsync[IO](Map(classOf[NoChangesV1] -> 100))
    val serializerV2 = KryoSerializer.forAsync[IO](Map(classOf[NonBreakingChangesV2] -> 100))

    for {
      bytes <- serializerV2.use { implicit kryo =>
        kryo.serialize(v2).liftTo[IO]
      }
      obj <- serializerV1.use { implicit kryo =>
        kryo.deserialize[NoChangesV1](bytes).liftTo[IO]
      }
      expectedV1 = NoChangesV1(amount = 15, address = "anyAddress")
    } yield expect.same(obj, expectedV1)

  }
}
