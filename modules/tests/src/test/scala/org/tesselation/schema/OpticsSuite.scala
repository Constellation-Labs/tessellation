package org.tesselation.schema

import java.util.UUID

import org.tesselation.domain.healthcheck.Status
import org.tesselation.optics.IsUUID

import monocle.law.discipline.IsoTests
import org.scalacheck.{Arbitrary, Cogen, Gen}
import weaver.FunSuite
import weaver.discipline.Discipline

object OpticsSuite extends FunSuite with Discipline {
  implicit val arbStatus: Arbitrary[Status] =
    Arbitrary(Gen.oneOf(Status.Okay, Status.Unreachable))

  implicit val uuidCogen: Cogen[UUID] =
    Cogen[(Long, Long)].contramap { uuid =>
      uuid.getLeastSignificantBits -> uuid.getMostSignificantBits
    }

  checkAll("Iso[Status._Bool", IsoTests(Status._Bool))

  checkAll("IsUUID[UUID", IsoTests(IsUUID[UUID]._UUID))
}
