package io.constellationnetwork.kryo

import cats.effect.IO
import cats.syntax.all._

import io.constellationnetwork.ext.kryo.KryoRegistrationId
import io.constellationnetwork.schema.GlobalSnapshot
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Interval
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

/** DEPRECATED: Kryo serialization test suite
  *
  * Status: All tests disabled due to Kryo removal Reason: Migrating from Kryo to JSON serialization for Java 21 compatibility
  *
  * These tests verified Kryo serialization versioning and migration logic. Similar tests should be created for JSON serialization if
  * versioning is needed.
  */
object KryoSerializerSuite extends SimpleIOSuite with Checkers {
  type KryoSerializerSuiteRegistrationIdRange = Interval.Closed[1000, 1999]

  test("v1 bytes should deserialize successfully by v2 serializer".ignore) {
    val v1 = NoChangesV1(amount = 15, address = "anyAddress")
    val migration = Migration { in: NoChangesV1 =>
      BreakingChangesClassV2(in.amount, "anyRemark")
    }

    val serializerV1 =
      KryoSerializer.forAsync[IO](
        Map[Class[_], KryoRegistrationId[KryoSerializerSuiteRegistrationIdRange]](classOf[NoChangesV1] -> 1000)
      )
    val serializerV2 =
      KryoSerializer
        .forAsync[IO](
          Map[Class[_], KryoRegistrationId[KryoSerializerSuiteRegistrationIdRange]](
            classOf[NoChangesV1] -> 1000,
            classOf[BreakingChangesClassV2] -> 1001
          ),
          List(migration)
        )

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

  test("deserialize returns Left instead of throwing LinkageError for corrupted bytes") {
    // Reproduces the testnet-20260413 crash on node .193:
    // Brotli-compressed incremental snapshot bytes happened to match a registered Kryo class ID
    // whose constructor could not be invoked by reflection (scala.collection.immutable.Range).
    // Kryo threw java.lang.InstantiationError, which Either.catchNonFatal does NOT catch —
    // crashing the process mid-rollback and causing a 12h restart loop.
    //
    // The fix extends KryoSerializer.deserialize to also catch LinkageError so the caller
    // can fall through to the alternate decoder.
    val badBytes = {
      val stream = getClass.getResourceAsStream("/bad-brotli-snapshot.br")
      try stream.readAllBytes()
      finally stream.close()
    }

    KryoSerializer.forAsync[IO](sharedKryoRegistrar).use { kryo =>
      IO {
        val result = kryo.deserialize[Signed[GlobalSnapshot]](badBytes)
        expect(result.isLeft, s"expected Left, got $result")
      }
    }
  }

  test("v2 bytes should deserialize successfully by v1 serializer".ignore) {
    val v2 = NonBreakingChangesV2(amount = 15, address = "anyAddress", remark = "remark")

    val serializerV1 =
      KryoSerializer.forAsync[IO](
        Map[Class[_], KryoRegistrationId[KryoSerializerSuiteRegistrationIdRange]](classOf[NoChangesV1] -> 1000)
      )
    val serializerV2 = KryoSerializer.forAsync[IO](
      Map[Class[_], KryoRegistrationId[KryoSerializerSuiteRegistrationIdRange]](classOf[NonBreakingChangesV2] -> 1000)
    )

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
