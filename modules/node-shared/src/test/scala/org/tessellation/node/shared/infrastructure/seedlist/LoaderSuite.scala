package org.tessellation.node.shared.infrastructure.seedlist

import cats.effect.IO
import cats.syntax.all._

import org.tessellation.env.env.SeedListPath
import org.tessellation.node.shared.domain.seedlist.SeedlistEntry.{Alias, AliasRefinement}
import org.tessellation.node.shared.domain.seedlist.snapshotOrdinalTimeline.{
  SnapshotOrdinalTimeline,
  optionalSnapshotOrdinalTimelineCellDecoder
}
import org.tessellation.node.shared.domain.seedlist.{ConnectionInfo, SeedlistEntry}
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.trust.TrustValueRefinement
import org.tessellation.security.hex.Hex

import com.comcast.ip4s.{IpAddress, Port}
import eu.timepit.refined.refineV
import fs2.io.file.Path
import weaver._
import weaver.scalacheck._

object LoaderSuite extends SimpleIOSuite with Checkers {

  def mkSeedlistEntry(
    peerId: String,
    ip: String = "",
    port: String = "",
    maybeAlias: Option[String] = none,
    maybeBias: Option[Double] = none,
    maybeOrdinalTimeline: Option[SnapshotOrdinalTimeline] = none
  ): SeedlistEntry = {
    val refinedMaybeBias = maybeBias.map(refineV[TrustValueRefinement].unsafeFrom(_))
    val refinedMaybeAlias = maybeAlias.map(refineV[AliasRefinement].unsafeFrom(_)).map(Alias(_))
    val connectionInfo = (IpAddress.fromString(ip), Port.fromString(port)).mapN {
      case (ipAddress, port) =>
        ConnectionInfo(ipAddress, port)
    }

    SeedlistEntry(
      PeerId(Hex(peerId)),
      connectionInfo,
      refinedMaybeAlias,
      refinedMaybeBias,
      maybeOrdinalTimeline
    )
  }

  test("load 1-field-format seedlist csv") {
    val testFileLocation = getClass.getResource("/seedlists/seedlist.1field.sample").getPath
    val inFile = Path(testFileLocation)

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

  test("load 5-fields-format seedlist csv") {
    val testFileLocation = getClass.getResource("/seedlists/seedlist.5fields.sample").getPath
    val inFile = Path(testFileLocation)

    val expected = Seq(
      mkSeedlistEntry(
        peerId =
          "3458a688925a4bd89f2ac2c695362e44d2e0c2903bdbb41b341a4d39283b22d8c85b487bd33cc5d36dbe5e31b5b00a10a6eab802718ead4ed7192ade5a5d1941",
        ip = "233.43.36.232",
        port = "11",
        maybeAlias = "test1".some,
        maybeBias = 1.0.some
      ),
      mkSeedlistEntry(
        peerId =
          "46daea11ca239cb8c0c8cdeb27db9dbe9c03744908a8a389a60d14df2ddde409260a93334d74957331eec1af323f458b12b3a6c3b8e05885608aae7e3a77eac7",
        ip = "33.82.174.165",
        port = "22",
        maybeAlias = "test2".some,
        maybeBias = 0.0.some
      ),
      mkSeedlistEntry(
        peerId =
          "e2f4496e5872682d7a55aa06e507a58e96b5d48a5286bfdff7ed780fa464d9e789b2760ecd840f4cb3ee6e1c1d81b2ee844c88dbebf149b1084b7313eb680714",
        ip = "107.114.18.119",
        port = "33",
        maybeAlias = "test3".some,
        maybeBias = Some(-1.0)
      ),
      mkSeedlistEntry(
        peerId =
          "ac5030e30ccf3c50b52b092c5b2b3a85ca13f72f85571b74f1ec062db7993e9d1c87edbb79b1aeed6038752fd6655dfd4995a0807067db2f20c1a63892d8f14c",
        ip = "189.122.117.43",
        port = "44",
        maybeAlias = "test4".some,
        maybeBias = 0.5.some
      ),
      mkSeedlistEntry(
        peerId =
          "ac5030e30ccf3c50b52b092c5b2b3a85ca13f72f85571b74f1ec062db7993e9d1c87edbb79b1aeed6038752fd6655dfd4995a0807067db2f20c1a63892d8f14c",
        ip = " 189.122.117.43 ",
        port = "56",
        maybeAlias = "test4".some,
        maybeBias = 0.5.some
      ),
      mkSeedlistEntry(
        peerId =
          "ac5030e30ccf3c50b52b092c5b2b3a85ca13f72f85571b74f1ec062db7993e9d1c87edbb79b1aeed6038752fd6655dfd4995a0807067db2f20c1a63892d8f14c",
        maybeAlias = "test4".some,
        maybeBias = 0.5.some
      ),
      mkSeedlistEntry(
        peerId =
          "ac5030e30ccf3c50b52b092c5b2b3a85ca13f72f85571b74f1ec062db7993e9d1c87edbb79b1aeed6038752fd6655dfd4995a0807067db2f20c1a63892d8f14c",
        maybeAlias = "test4".some,
        maybeBias = 0.5.some
      )
    ).toSet

    Loader
      .make[IO]
      .load(SeedListPath(inFile))
      .map(
        expect.eql(expected, _)
      )
  }

  test("load mixed-format seedlist csv") {
    val testFileLocation = getClass.getResource("/seedlists/seedlist.mixedFields.sample").getPath
    val inFile = Path(testFileLocation)

    val expected = Seq(
      mkSeedlistEntry(
        peerId =
          "3458a688925a4bd89f2ac2c695362e44d2e0c2903bdbb41b341a4d39283b22d8c85b487bd33cc5d36dbe5e31b5b00a10a6eab802718ead4ed7192ade5a5d1941"
      ),
      mkSeedlistEntry(
        peerId =
          "46daea11ca239cb8c0c8cdeb27db9dbe9c03744908a8a389a60d14df2ddde409260a93334d74957331eec1af323f458b12b3a6c3b8e05885608aae7e3a77eac7",
        ip = "33.82.174.165",
        port = "11",
        maybeAlias = "test2".some,
        maybeBias = 0.0.some
      ),
      mkSeedlistEntry(
        peerId =
          "e2f4496e5872682d7a55aa06e507a58e96b5d48a5286bfdff7ed780fa464d9e789b2760ecd840f4cb3ee6e1c1d81b2ee844c88dbebf149b1084b7313eb680714"
      ),
      mkSeedlistEntry(
        peerId =
          "ac5030e30ccf3c50b52b092c5b2b3a85ca13f72f85571b74f1ec062db7993e9d1c87edbb79b1aeed6038752fd6655dfd4995a0807067db2f20c1a63892d8f14c",
        ip = "189.122.117.43",
        port = "22",
        maybeAlias = "test4".some,
        maybeBias = 0.5.some
      ),
      mkSeedlistEntry(
        peerId =
          "ac5030e30ccf3c50b52b092c5b2b3a85ca13f72f85571b74f1ec062db7993e9d1c87edbb79b1aeed6038752fd6655dfd4995a0807067db2f20c1a63892d8f14c",
        ip = "189.122.117.43",
        port = "33",
        maybeAlias = "test4".some,
        maybeBias = 0.5.some,
        maybeOrdinalTimeline = optionalSnapshotOrdinalTimelineCellDecoder("0-49;50-50; 60-61").toOption.collect {
          case Some(a) => a
        }
      ),
      mkSeedlistEntry(
        peerId =
          "ac5030e30ccf3c50b52b092c5b2b3a85ca13f72f85571b74f1ec062db7993e9d1c87edbb79b1aeed6038752fd6655dfd4995a0807067db2f20c1a63892d8f14c",
        maybeAlias = "test4".some,
        maybeBias = 0.5.some,
        maybeOrdinalTimeline = optionalSnapshotOrdinalTimelineCellDecoder("0-49;50-50; 60-61").toOption.collect {
          case Some(a) => a
        }
      ),
      mkSeedlistEntry(
        peerId =
          "ac5030e30ccf3c50b52b092c5b2b3a85ca13f72f85571b74f1ec062db7993e9d1c87edbb79b1aeed6038752fd6655dfd4995a0807067db2f20c1a63892d8f14c",
        maybeAlias = "test4".some,
        maybeBias = 0.5.some,
        maybeOrdinalTimeline = optionalSnapshotOrdinalTimelineCellDecoder("0-49;50-50; 60-61").toOption.collect {
          case Some(a) => a
        }
      )
    ).toSet

    Loader.make[IO].load(SeedListPath(inFile)).map(expect.eql(expected, _))
  }

  test("load 6-field-format seedlist csv") {
    val testFileLocation = getClass.getResource("/seedlists/seedlist.6fields.sample").getPath
    val inFile = Path(testFileLocation)

    val expected = Seq(
      mkSeedlistEntry(
        peerId =
          "3458a688925a4bd89f2ac2c695362e44d2e0c2903bdbb41b341a4d39283b22d8c85b487bd33cc5d36dbe5e31b5b00a10a6eab802718ead4ed7192ade5a5d1941",
        ip = "233.43.36.232",
        port = "89",
        maybeAlias = "test1".some,
        maybeBias = 1.0.some,
        maybeOrdinalTimeline = optionalSnapshotOrdinalTimelineCellDecoder("0-100").toOption.collect {
          case Some(a) => a
        }
      ),
      mkSeedlistEntry(
        peerId =
          "46daea11ca239cb8c0c8cdeb27db9dbe9c03744908a8a389a60d14df2ddde409260a93334d74957331eec1af323f458b12b3a6c3b8e05885608aae7e3a77eac7",
        ip = "33.82.174.165",
        port = "0",
        maybeAlias = "test2".some,
        maybeBias = 0.0.some,
        maybeOrdinalTimeline = optionalSnapshotOrdinalTimelineCellDecoder("0-49;50-50").toOption.collect {
          case Some(a) => a
        }
      ),
      mkSeedlistEntry(
        peerId =
          "e2f4496e5872682d7a55aa06e507a58e96b5d48a5286bfdff7ed780fa464d9e789b2760ecd840f4cb3ee6e1c1d81b2ee844c88dbebf149b1084b7313eb680714",
        ip = "107.114.18.119",
        port = "11",
        maybeAlias = "test3".some,
        maybeBias = Some(-1.0),
        maybeOrdinalTimeline = optionalSnapshotOrdinalTimelineCellDecoder("0-49;50-50;60-61").toOption.collect {
          case Some(a) => a
        }
      ),
      mkSeedlistEntry(
        peerId =
          "ac5030e30ccf3c50b52b092c5b2b3a85ca13f72f85571b74f1ec062db7993e9d1c87edbb79b1aeed6038752fd6655dfd4995a0807067db2f20c1a63892d8f14c",
        ip = "189.122.117.43",
        port = "22",
        maybeAlias = "test4".some,
        maybeBias = 0.5.some,
        maybeOrdinalTimeline = optionalSnapshotOrdinalTimelineCellDecoder("0-49;50-50;60-61;80-89").toOption.collect {
          case Some(a) => a
        }
      ),
      mkSeedlistEntry(
        peerId =
          "ac5030e30ccf3c50b52b092c5b2b3a85ca13f72f85571b74f1ec062db7993e9d1c87edbb79b1aeed6038752fd6655dfd4995a0807067db2f20c1a63892d8f14c",
        ip = "189.122.117.43",
        port = "33",
        maybeAlias = "test4".some,
        maybeBias = 0.5.some,
        maybeOrdinalTimeline = optionalSnapshotOrdinalTimelineCellDecoder("0-49;50-50;60-61;80-89;90").toOption.collect {
          case Some(a) => a
        }
      )
    ).toSet

    Loader
      .make[IO]
      .load(SeedListPath(inFile))
      .map(
        expect.eql(expected, _)
      )
  }

  test("load invalid 6-fields-format seedlist csv") {
    val testFileLocation = getClass.getResource("/seedlists/seedlist.6fields.invalid.sample").getPath
    val inFile = Path(testFileLocation)

    for {
      loader <- Loader.make[IO].load(SeedListPath(inFile)).attempt
      maybeErrorMessage = loader.left.map(_.getMessage)
    } yield expect.eql(true, maybeErrorMessage.isLeft)
  }

  test("load invalid-format seedlist csv") {
    val testFileLocation = getClass.getResource("/seedlists/seedlist.invalidFieldCounts.sample").getPath
    val inFile = Path(testFileLocation)

    for {
      loader <- Loader.make[IO].load(SeedListPath(inFile)).attempt
      maybeErrorMessage = loader.left.map(_.getMessage)
    } yield expect.eql(maybeErrorMessage, Left("Rows must have 1, 5, or 6 fields, but found 4"))
  }

}
