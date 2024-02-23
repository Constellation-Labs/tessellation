package org.tessellation.dag.l0.http.routes

import cats.effect._
import cats.syntax.option._

import scala.reflect.runtime.universe.TypeTag

import org.tessellation.dag.l0.dagL0KryoRegistrar
import org.tessellation.dag.l0.domain.cluster.programs.TrustPush
import org.tessellation.dag.l0.infrastructure.trust.generators.genPeerTrustInfo
import org.tessellation.dag.l0.infrastructure.trust.storage.TrustStorage
import org.tessellation.ext.kryo._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.config.types.TrustStorageConfig
import org.tessellation.node.shared.domain.gossip.Gossip
import org.tessellation.node.shared.domain.trust.storage._
import org.tessellation.node.shared.nodeSharedKryoRegistrar
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.generators._
import org.tessellation.schema.trust.{PeerObservationAdjustmentUpdate, PeerObservationAdjustmentUpdateBatch, TrustScores}

import eu.timepit.refined.auto._
import io.circe.Encoder
import io.circe.syntax.EncoderOps
import org.http4s.Method.GET
import org.http4s._
import org.http4s.client.dsl.io._
import org.http4s.syntax.literals._
import org.scalacheck.Gen
import suite.HttpSuite

object TrustRoutesSuite extends HttpSuite {

  def kryoSerializerResource: Resource[IO, KryoSerializer[IO]] = KryoSerializer
    .forAsync[IO](nodeSharedKryoRegistrar.union(dagL0KryoRegistrar))

  private def mkTrustStorage(trust: TrustMap = TrustMap.empty): F[TrustStorage[F]] = {
    val config = TrustStorageConfig(
      ordinalTrustUpdateInterval = 1000L,
      ordinalTrustUpdateDelay = 500L,
      seedlistInputBias = 0.7,
      seedlistOutputBias = 0.5
    )

    TrustStorage.make(trust, config, none)
  }

  private def mkP2pRoutes(
    trustStorage: TrustStorage[F],
    peerObservationAdjustmentUpdate: List[PeerObservationAdjustmentUpdate]
  ) = {
    val gossip = new Gossip[IO] {
      override def spread[A: TypeTag: Encoder](rumorContent: A): IO[Unit] = IO.unit

      override def spreadCommon[A: TypeTag: Encoder](rumorContent: A): IO[Unit] = IO.unit
    }
    val trustPush = TrustPush.make[IO](trustStorage, gossip)

    trustStorage.updateTrust {
      PeerObservationAdjustmentUpdateBatch(peerObservationAdjustmentUpdate)
    }.map(_ => TrustRoutes[IO](trustStorage, trustPush).p2pRoutes)
  }

  private def mkPublicRoutes(
    trustStorage: TrustStorage[F],
    peerObservationAdjustmentUpdate: List[PeerObservationAdjustmentUpdate] = List.empty
  ) = {
    val gossip = new Gossip[IO] {
      override def spread[A: TypeTag: Encoder](rumorContent: A): IO[Unit] = IO.unit

      override def spreadCommon[A: TypeTag: Encoder](rumorContent: A): IO[Unit] = IO.unit
    }
    val trustPush = TrustPush.make[IO](trustStorage, gossip)

    trustStorage.updateTrust {
      PeerObservationAdjustmentUpdateBatch(peerObservationAdjustmentUpdate)
    }.map(_ => TrustRoutes[IO](trustStorage, trustPush).publicRoutes)
  }

  private val genTrustMap = for {
    numTrusts <- Gen.chooseNum(0, 10)
    trustInfo <- Gen.mapOfN(numTrusts, genPeerTrustInfo)
  } yield TrustMap(trust = trustInfo, PublicTrustMap.empty)

  test("GET trust succeeds") {
    val req = GET(uri"/trust")
    val peer = (for {
      peers <- peersGen()
    } yield peers.head).sample.get

    for {
      ts <- mkTrustStorage()
      peerObservationAdjustmentUpdate = List(PeerObservationAdjustmentUpdate(peer.id, 0.5))
      routes <- mkP2pRoutes(ts, peerObservationAdjustmentUpdate)

      expected <- ts.getPublicTrust.map(_.asJson)
      testResults <- expectHttpBodyAndStatus(routes, req)(expected, Status.Ok)
    } yield testResults
  }

  test("GET current trust succeeds") {
    val req = GET(uri"/trust/current")

    forall(genTrustMap) { trust =>
      for {
        ts <- mkTrustStorage(trust)
        routes <- mkPublicRoutes(ts)

        expected = TrustScores(
          trust.trust.view
            .mapValues(trustInfo => trustInfo.predictedTrust.orElse(trustInfo.trustLabel))
            .collect { case (k, Some(v)) => (k, v) }
            .toMap
        ).asJson
        testResult <- expectHttpBodyAndStatus(routes, req)(expected, Status.Ok)
      } yield testResult
    }
  }

  test("GET previous trust succeeds") {
    val req = GET(uri"/trust/previous")

    forall(genTrustMap) { trust =>
      for {
        ts <- mkTrustStorage(trust)
        _ <- ts.updateNext(SnapshotOrdinal(1000L))
        _ <- ts.updateCurrent(SnapshotOrdinal(1500L))

        routes <- mkPublicRoutes(ts)

        expected = OrdinalTrustMap(SnapshotOrdinal(1000L), PublicTrustMap.empty, trust).asJson
        testResult <- expectHttpBodyAndStatus(routes, req)(expected, Status.Ok)
      } yield testResult
    }
  }

}
