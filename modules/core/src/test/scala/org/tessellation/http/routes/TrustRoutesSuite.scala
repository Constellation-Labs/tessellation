package org.tessellation.http.routes

import cats.effect._
import cats.effect.unsafe.implicits.global

import org.tessellation.infrastructure.trust.storage.TrustStorage
import org.tessellation.kryo.{KryoSerializer, coreKryoRegistrar}
import org.tessellation.schema.generators._
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.trust.{InternalTrustUpdate, InternalTrustUpdateBatch, TrustInfo}
import org.tessellation.sdk.kryo.sdkKryoRegistrar

import org.http4s.Method._
import org.http4s._
import org.http4s.client.dsl.io._
import org.http4s.syntax.literals._
import suite.HttpSuite

object TrustRoutesSuite extends HttpSuite {
  test("GET trust succeeds") {
    val req = GET(uri"/trust")
    val peer = (for {
      peers <- peersGen()
    } yield peers.head).sample.get

    KryoSerializer
      .forAsync[IO](sdkKryoRegistrar ++ coreKryoRegistrar)
      .use { implicit kryoPool =>
        for {
          trust <- Ref[IO].of(Map.empty[PeerId, TrustInfo])
          ts = TrustStorage.make[IO](trust)
          _ <- ts.updateTrust(
            InternalTrustUpdateBatch(List(InternalTrustUpdate(peer.id, 0.5)))
          )
          routes = TrustRoutes[IO](ts).p2pRoutes
        } yield expectHttpStatus(routes, req)(Status.Ok)
      }
      .unsafeRunSync()
  }
}
