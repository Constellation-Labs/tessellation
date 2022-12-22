package org.tessellation.schema

import java.util.UUID

import org.tessellation.optics.IsUUID
import org.tessellation.schema.ID.Id
import org.tessellation.schema.generators.peerResponsivenessGen
import org.tessellation.schema.peer.{PeerId, PeerResponsiveness}
import org.tessellation.schema.security.hex.Hex

import io.estatico.newtype.ops._
import monocle.law.discipline.IsoTests
import org.scalacheck.{Arbitrary, Cogen, Gen}
import weaver.FunSuite
import weaver.discipline.Discipline

object OpticsSuite extends FunSuite with Discipline {
  implicit val arbPeerResponsiveness: Arbitrary[PeerResponsiveness] =
    Arbitrary(peerResponsivenessGen)

  implicit val arbPeerID: Arbitrary[PeerId] =
    Arbitrary(Gen.alphaStr.map(Hex(_)).map(PeerId(_)))
  implicit val arbPeerId: Arbitrary[Id] =
    Arbitrary(Gen.alphaStr.map(Hex(_)).map(Id(_)))

  implicit val hexCogen: Cogen[Hex] =
    Cogen[String].contramap(_.coerce)

  implicit val idCogen: Cogen[Id] =
    Cogen[Hex].contramap(_.hex)

  implicit val uuidCogen: Cogen[UUID] =
    Cogen[(Long, Long)].contramap { uuid =>
      uuid.getLeastSignificantBits -> uuid.getMostSignificantBits
    }

  checkAll("Iso[PeerResponsiveness._Bool", IsoTests(PeerResponsiveness._Bool))

  checkAll("Iso[PeerId._Id", IsoTests(PeerId._Id))

  checkAll("IsUUID[UUID", IsoTests(IsUUID[UUID]._UUID))
}
