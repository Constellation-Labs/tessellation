package org.tessellation.infrastructure.trust.storage

import cats.effect.IO

import org.tessellation.coreKryoRegistrar
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.generators._
import org.tessellation.schema.trust.{InternalTrustUpdate, InternalTrustUpdateBatch}
import org.tessellation.sdk.sdkKryoRegistrar

import fs2.io.file.Path
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object TrustStorageSuite extends SimpleIOSuite with Checkers {

  test("trust update is applied") {
    KryoSerializer
      .forAsync[IO](sdkKryoRegistrar ++ coreKryoRegistrar)
      .use { implicit kryoPool =>
        forall(peerGen) { peer =>
          for {
            cs <- TrustStorage.make[IO](Path("tmp/test2"), diskEnabled = false)
            _ <- cs.updateTrust(
              InternalTrustUpdateBatch(List(InternalTrustUpdate(peer.id, 0.5)))
            )
            updatedTrust <- cs.getTrust
          } yield expect(updatedTrust(peer.id).trustLabel.get == 0.5)
        }
      }
  }

  test("trust update is applied to disk") {
    KryoSerializer
      .forAsync[IO](sdkKryoRegistrar ++ coreKryoRegistrar)
      .use { implicit kryoPool =>
        Path("./data").toNioPath.toFile.delete()
        val peer = peerGen.sample.get
        for {
          cs <- TrustStorage.make[IO](Path("."), diskEnabled = true)
          _ <- cs.updateTrust(
            InternalTrustUpdateBatch(List(InternalTrustUpdate(peer.id, 0.5)))
          )
          cs2 <- TrustStorage.make[IO](Path("."), diskEnabled = true)
          updatedTrust <- cs2.getTrust
        } yield {
          Path("./data").toNioPath.toFile.delete()
          expect(updatedTrust(peer.id).trustLabel.get == 0.5)
        }
      }
  }

}
