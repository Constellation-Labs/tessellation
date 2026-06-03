package io.constellationnetwork.node.shared.http.routes

import cats.effect.IO

import org.http4s._
import weaver.SimpleIOSuite

object SnapshotRoutesHeavyweightSuite extends SimpleIOSuite {

  private def reqAt(path: String): Request[IO] =
    Request[IO](method = Method.GET, uri = Uri.unsafeFromString(path))

  pureTest("latest combined cached route is heavyweight") {
    val req = reqAt("/latest/combined")

    expect.same(Some("latest_combined"), SnapshotRoutes.heavyweightEndpoint(req)) &&
    expect(SnapshotRoutes.isHeavyweightSnapshotRoute(req))
  }

  pureTest("latest combined stream route is heavyweight") {
    val req = reqAt("/latest/combined/stream")

    expect.same(Some("latest_combined_stream"), SnapshotRoutes.heavyweightEndpoint(req)) &&
    expect(SnapshotRoutes.isHeavyweightSnapshotRoute(req))
  }

  pureTest("combined checkpoint info remains lightweight") {
    val req = reqAt("/latest/combined/checkpoint/info")

    expect.same(None, SnapshotRoutes.heavyweightEndpoint(req)) &&
    expect(!SnapshotRoutes.isHeavyweightSnapshotRoute(req))
  }
}
