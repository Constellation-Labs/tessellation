package org.tessellation.sdk.infrastructure.seedlist

import java.nio.file.Paths

import cats.effect.IO
import cats.syntax.option._

import org.tessellation.cli.env.SeedListPath
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.trust.TrustValueRefinement
import org.tessellation.sdk.domain.seedlist.{Alias, SeedlistEntry}
import org.tessellation.security.hex.Hex

import com.comcast.ip4s.IpAddress
import eu.timepit.refined.refineV
import fs2.io.file.Path
import weaver._
import weaver.scalacheck._

object LoaderSuite extends SimpleIOSuite with Checkers {
  def mkSeedlistEntry(
    peerId: String,
    ip: String = "",
    alias: Option[String] = none,
    bias: Option[Double] = none
  ): SeedlistEntry = {
    val refinedMaybeBias = bias.map(refineV[TrustValueRefinement](_).toOption.get)
    val ipAddress = IpAddress.fromString(ip)

    SeedlistEntry(
      PeerId(Hex(peerId)),
      ipAddress,
      alias.map(Alias(_)),
      refinedMaybeBias
    )
  }

  test("load old-format seedlist csv") {
    val testFileLocation = getClass.getResource("/seedlists/seedlist.1field.sample")
    val inFile = Path.fromNioPath(Paths.get(testFileLocation.toURI))

    val expected = Seq(
      mkSeedlistEntry(
        peerId =
          "3458a688925a4bd89f2ac2c695362e44d2e0c2903bdbb41b341a4d39283b22d8c85b487bd33cc5d36dbe5e31b5b00a10a6eab802718ead4ed7192ade5a5d1941"
      ),
      mkSeedlistEntry(
        peerId =
          "46daea11ca239cb8c0c8cdeb27db9dbe9c03744908a8a389a60d14df2ddde409260a93334d74957331eec1af323f458b12b3a6c3b8e05885608aae7e3a77eac7"
      ),
      mkSeedlistEntry(
        peerId =
          "e2f4496e5872682d7a55aa06e507a58e96b5d48a5286bfdff7ed780fa464d9e789b2760ecd840f4cb3ee6e1c1d81b2ee844c88dbebf149b1084b7313eb680714"
      ),
      mkSeedlistEntry(
        peerId =
          "ac5030e30ccf3c50b52b092c5b2b3a85ca13f72f85571b74f1ec062db7993e9d1c87edbb79b1aeed6038752fd6655dfd4995a0807067db2f20c1a63892d8f14c"
      )
    ).toSet

    Loader.make[IO].load(SeedListPath(inFile)).map(expect.eql(expected, _))
  }

  test("load new-format seedlist csv") {
    val testFileLocation = getClass.getResource("/seedlists/seedlist.4fields.sample")
    val inFile = Path.fromNioPath(Paths.get(testFileLocation.toURI))

    val expected = Seq(
      mkSeedlistEntry(
        peerId =
          "3458a688925a4bd89f2ac2c695362e44d2e0c2903bdbb41b341a4d39283b22d8c85b487bd33cc5d36dbe5e31b5b00a10a6eab802718ead4ed7192ade5a5d1941",
        ip = "233.43.36.232",
        alias = "test1".some,
        bias = 1.0.some
      ),
      mkSeedlistEntry(
        peerId =
          "46daea11ca239cb8c0c8cdeb27db9dbe9c03744908a8a389a60d14df2ddde409260a93334d74957331eec1af323f458b12b3a6c3b8e05885608aae7e3a77eac7",
        ip = "33.82.174.165",
        alias = "test2".some,
        bias = 0.0.some
      ),
      mkSeedlistEntry(
        peerId =
          "e2f4496e5872682d7a55aa06e507a58e96b5d48a5286bfdff7ed780fa464d9e789b2760ecd840f4cb3ee6e1c1d81b2ee844c88dbebf149b1084b7313eb680714",
        ip = "107.114.18.119",
        alias = "test3".some,
        bias = Some(-1.0)
      ),
      mkSeedlistEntry(
        peerId =
          "ac5030e30ccf3c50b52b092c5b2b3a85ca13f72f85571b74f1ec062db7993e9d1c87edbb79b1aeed6038752fd6655dfd4995a0807067db2f20c1a63892d8f14c",
        ip = "189.122.117.43",
        alias = "test4".some,
        bias = 0.5.some
      )
    ).toSet

    Loader.make[IO].load(SeedListPath(inFile)).map(expect.eql(expected, _))
  }

  test("load mixed-format seedlist csv") {
    val testFileLocation = getClass.getResource("/seedlists/seedlist.mixedFields.sample")
    val inFile = Path.fromNioPath(Paths.get(testFileLocation.toURI))

    val expected = Seq(
      mkSeedlistEntry(
        peerId =
          "3458a688925a4bd89f2ac2c695362e44d2e0c2903bdbb41b341a4d39283b22d8c85b487bd33cc5d36dbe5e31b5b00a10a6eab802718ead4ed7192ade5a5d1941"
      ),
      mkSeedlistEntry(
        peerId =
          "46daea11ca239cb8c0c8cdeb27db9dbe9c03744908a8a389a60d14df2ddde409260a93334d74957331eec1af323f458b12b3a6c3b8e05885608aae7e3a77eac7",
        ip = "33.82.174.165",
        alias = "test2".some,
        bias = 0.0.some
      ),
      mkSeedlistEntry(
        peerId =
          "e2f4496e5872682d7a55aa06e507a58e96b5d48a5286bfdff7ed780fa464d9e789b2760ecd840f4cb3ee6e1c1d81b2ee844c88dbebf149b1084b7313eb680714"
      ),
      mkSeedlistEntry(
        peerId =
          "ac5030e30ccf3c50b52b092c5b2b3a85ca13f72f85571b74f1ec062db7993e9d1c87edbb79b1aeed6038752fd6655dfd4995a0807067db2f20c1a63892d8f14c",
        ip = "189.122.117.43",
        alias = "test4".some,
        bias = 0.5.some
      )
    ).toSet

    Loader.make[IO].load(SeedListPath(inFile)).map(expect.eql(expected, _))
  }

  test("load invalid-format seedlist csv") {
    val testFileLocation = getClass.getResource("/seedlists/seedlist.invalid.sample")
    val inFile = Path.fromNioPath(Paths.get(testFileLocation.toURI))

    for {
      loader <- Loader.make[IO].load(SeedListPath(inFile)).attempt
      maybeErrorMessage = loader.left.map(_.getMessage)
    } yield expect.eql(maybeErrorMessage, Left("Rows must have 1 or 4 fields, but found 3"))
  }

}
