package org.tessellation.schema

import java.util.UUID

import org.tessellation.optics.IsUUID
import org.tessellation.schema.generators.peerResponsivenessGen
import org.tessellation.schema.hex.HexString128
import org.tessellation.schema.id.Id
import org.tessellation.schema.peer.{PeerId, PeerResponsiveness}

import monocle.law.discipline.IsoTests
import org.scalacheck.{Arbitrary, Cogen}
import weaver.FunSuite
import weaver.discipline.Discipline

object OpticsSuite extends FunSuite with Discipline {
  implicit val arbPeerResponsiveness: Arbitrary[PeerResponsiveness] =
    Arbitrary(peerResponsivenessGen)

  implicit val hexCogen: Cogen[HexString128] =
    Cogen[String].contramap(_.value)

  implicit val idCogen: Cogen[Id] =
    Cogen[HexString128].contramap(_.hex)

  implicit val uuidCogen: Cogen[UUID] =
    Cogen[(Long, Long)].contramap { uuid =>
      uuid.getLeastSignificantBits -> uuid.getMostSignificantBits
    }

  checkAll("Iso[PeerResponsiveness._Bool", IsoTests(PeerResponsiveness._Bool))

  checkAll("Iso[PeerId._Id", IsoTests(PeerId._Id))

  checkAll("IsUUID[UUID", IsoTests(IsUUID[UUID]._UUID))
}
