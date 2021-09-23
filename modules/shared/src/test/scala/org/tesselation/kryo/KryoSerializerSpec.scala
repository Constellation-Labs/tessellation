package org.tesselation.kryo

import cats.effect._
import cats.effect.testing.scalatest.{AsyncIOSpec, CatsResourceIO}
import org.scalatest.matchers.should.Matchers
import org.scalatest.freespec.AsyncFreeSpec
import cats.syntax.all._

class KryoSerializerSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers {

  "forward compatibility" - {
    "v1 bytes should deserialize successfully by v2 serializer" in {
      val v1 = NoChangesV1(amount = 15, address = "anyAddress")
      val migration = Migration { in: NoChangesV1 =>
        BreakingChangesClassV2(in.amount, "anyRemark")
      }

      val serializerV1 = KryoSerializer.forAsync[IO](Map(classOf[NoChangesV1] -> 100))
      val serializerV2 =
        KryoSerializer
          .forAsync[IO](Map(classOf[NoChangesV1] -> 100, classOf[BreakingChangesClassV2] -> 101), List(migration))

      val result = for {
        bytes <- serializerV1.use { implicit kryo =>
          kryo.serialize(v1).liftTo[IO]
        }
        obj <- serializerV2.use { implicit kryo =>
          kryo.deserialize[BreakingChangesClassV2](bytes).liftTo[IO]
        }
      } yield obj

      val expectedV2 = BreakingChangesClassV2(amount = 15, remark = "anyRemark")
      result.asserting(_ shouldBe expectedV2)
    }
  }

  "backward compatibility" - {
    "v2 bytes should deserialize successfully by v1 serializer" in {
      val v2 = NonBreakingChangesV2(amount = 15, address = "anyAddress", remark = "remark")

      val serializerV1 = KryoSerializer.forAsync[IO](Map(classOf[NoChangesV1] -> 100))
      val serializerV2 = KryoSerializer.forAsync[IO](Map(classOf[NonBreakingChangesV2] -> 100))

      val result = for {
        bytes <- serializerV2.use { implicit kryo =>
          kryo.serialize(v2).liftTo[IO]
        }
        obj <- serializerV1.use { implicit kryo =>
          kryo.deserialize[NoChangesV1](bytes).liftTo[IO]
        }
      } yield obj

      val expectedV2 = NoChangesV1(amount = 15, address = "anyAddress")
      result.asserting(_ shouldBe expectedV2)
    }
  }
}
