package org.tesselation.infrastructure.trust.storage

import cats.effect.{IO, Ref}

import org.tesselation.schema.generators._
import org.tesselation.schema.peer.PeerId
import org.tesselation.schema.trust.{InternalTrustUpdate, InternalTrustUpdateBatch, TrustInfo}

import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object TrustStorageSuite extends SimpleIOSuite with Checkers {

  test("trust update is applied") {
    forall(peerGen) { peer =>
      for {
        trust <- Ref[IO].of(Map.empty[PeerId, TrustInfo])
        cs = TrustStorage.make[IO](trust)
        _ <- cs.updateTrust(
          InternalTrustUpdateBatch(List(InternalTrustUpdate(peer.id, 0.5)))
        )
        updatedTrust <- cs.getTrust
      } yield expect(updatedTrust(peer.id).trustLabel.get == 0.5)
    }
  }
}
