package org.tessellation.sdk.infrastructure.trust

import java.nio.file.Paths

import cats.effect.IO

import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.trust.{PeerObservationAdjustmentUpdate, PeerObservationAdjustmentUpdateBatch}
import org.tessellation.security.hex.Hex

import eu.timepit.refined.api.Refined
import fs2.io.file.Path
import weaver._
import weaver.scalacheck._

object TrustRatingCsvLoaderSuite extends SimpleIOSuite with Checkers {
  test("load trust ratings from csv") {
    val testFileLocation = getClass.getResource("/ratings.sample.csv")
    val inFile = Path.fromNioPath(Paths.get(testFileLocation.toURI))

    val expectedRatings = List(
      "3458a688925a4bd89f2ac2c695362e44d2e0c2903bdbb41b341a4d39283b22d8c85b487bd33cc5d36dbe5e31b5b00a10a6eab802718ead4ed7192ade5a5d1941" -> -1.0,
      "46daea11ca239cb8c0c8cdeb27db9dbe9c03744908a8a389a60d14df2ddde409260a93334d74957331eec1af323f458b12b3a6c3b8e05885608aae7e3a77eac7" -> 0.0,
      "e2f4496e5872682d7a55aa06e507a58e96b5d48a5286bfdff7ed780fa464d9e789b2760ecd840f4cb3ee6e1c1d81b2ee844c88dbebf149b1084b7313eb680714" -> 1.0,
      "ac5030e30ccf3c50b52b092c5b2b3a85ca13f72f85571b74f1ec062db7993e9d1c87edbb79b1aeed6038752fd6655dfd4995a0807067db2f20c1a63892d8f14c" -> 0.5
    ).map { case (h, d) => PeerObservationAdjustmentUpdate(PeerId(Hex(h)), Refined.unsafeApply(d)) }

    TrustRatingCsvLoader.make[IO].load(inFile).map(expect.eql(PeerObservationAdjustmentUpdateBatch(expectedRatings), _))
  }
}
