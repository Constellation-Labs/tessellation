package org.tesselation.schema

import java.util.UUID

import org.tesselation.optics.IsUUID
import org.tesselation.schema.ID.Id
import org.tesselation.schema.healthcheck.Status
import org.tesselation.schema.peer.PeerId

import monocle.law.discipline.IsoTests
import org.scalacheck.{Arbitrary, Cogen, Gen}
import weaver.FunSuite
import weaver.discipline.Discipline

object OpticsSuite extends FunSuite with Discipline {
  implicit val arbStatus: Arbitrary[Status] =
    Arbitrary(Gen.oneOf(Status.Okay, Status.Unreachable))

  implicit val arbPeerID: Arbitrary[PeerId] =
    Arbitrary(Gen.alphaStr.map(PeerId(_)))
  implicit val arbPeerId: Arbitrary[Id] =
    Arbitrary(Gen.alphaStr.map(Id(_)))

  implicit val idCogen: Cogen[Id] =
    Cogen[String].contramap(_.hex)

  implicit val uuidCogen: Cogen[UUID] =
    Cogen[(Long, Long)].contramap { uuid =>
      uuid.getLeastSignificantBits -> uuid.getMostSignificantBits
    }

  checkAll("Iso[Status._Bool", IsoTests(Status._Bool))

  checkAll("Iso[PeerId._Id", IsoTests(PeerId._Id))

  checkAll("IsUUID[UUID", IsoTests(IsUUID[UUID]._UUID))
}
