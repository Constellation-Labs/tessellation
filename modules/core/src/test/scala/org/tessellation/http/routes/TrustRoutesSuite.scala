package org.tessellation.http.routes

import cats.effect._
import cats.syntax.option._

import scala.reflect.runtime.universe.TypeTag

import org.tessellation.coreKryoRegistrar
import org.tessellation.domain.cluster.programs.TrustPush
import org.tessellation.ext.kryo._
import org.tessellation.infrastructure.trust.generators.{genPeerPublicTrust, genPeerTrustInfo}
import org.tessellation.infrastructure.trust.storage.TrustStorage
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.generators._
import org.tessellation.schema.trust._
import org.tessellation.sdk.config.types.TrustStorageConfig
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.seedlist.SeedlistEntry
import org.tessellation.sdk.domain.trust.storage._
import org.tessellation.sdk.sdkKryoRegistrar

import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.NonNegative
import io.circe.Encoder
import io.circe.syntax.EncoderOps
import org.http4s.Method.GET
import org.http4s._
import org.http4s.client.dsl.io._
import org.http4s.syntax.literals._
import org.scalacheck.Gen
import suite.HttpSuite

object TrustRoutesSuite extends HttpSuite {

  val config = TrustStorageConfig(
    ordinalTrustUpdateInterval = 1000L,
    ordinalTrustUpdateDelay = 500L,
    seedlistInputBias = 0.7,
    seedlistOutputBias = 0.5
  )

  def kryoSerializerResource: Resource[IO, KryoSerializer[IO]] = KryoSerializer
    .forAsync[IO](sdkKryoRegistrar.union(coreKryoRegistrar))

  private def mkTrustStorage(
    trust: TrustMap = TrustMap.empty,
    seedlistEntries: Set[SeedlistEntry] = Set.empty[SeedlistEntry]
  ): F[TrustStorage[F]] =
    TrustStorage.make(trust, config, seedlistEntries)

  private def mkP2pRoutes(
    trustStorage: TrustStorage[F],
    peerObservationAdjustmentUpdate: List[PeerObservationAdjustmentUpdate] = List.empty
  ) =
    kryoSerializerResource.use { implicit kryo =>
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
  ) =
    kryoSerializerResource.use { implicit kryo =>
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
  } yield TrustMap(trust = trustInfo, peerLabels = PublicTrustMap.empty)

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
        _ <- ts.updateNext(SnapshotOrdinal(config.ordinalTrustUpdateInterval))
        _ <- ts.updateCurrent(
          SnapshotOrdinal(
            Refined.unsafeApply[Long, NonNegative](
              config.ordinalTrustUpdateInterval + config.ordinalTrustUpdateDelay
            )
          )
        )

        routes <- mkPublicRoutes(ts)

        expected = OrdinalTrustMap(
          SnapshotOrdinal(config.ordinalTrustUpdateInterval),
          PublicTrustMap.empty,
          trust
        ).asJson
        testResult <- expectHttpBodyAndStatus(routes, req)(expected, Status.Ok)
      } yield testResult
    }
  }

  test("GET previous peer-labels succeeds") {
    val req = GET(uri"/trust/previous/peer-labels")

    val gen = for {
      trustMap <- genTrustMap
      peerId <- peerIdGen
      score <- Gen.chooseNum(0.1, 1.0).map(Refined.unsafeApply[Double, TrustValueRefinement])
      seedlistEntries = Set(SeedlistEntry(peerId, none, none, score.some))
      (_, publicTrust) <- genPeerPublicTrust
      snapshotOrdinal = SnapshotOrdinal(config.ordinalTrustUpdateInterval)
      snapshotOrdinalPublicTrust = SnapshotOrdinalPublicTrust(snapshotOrdinal, publicTrust)
    } yield (trustMap, seedlistEntries, peerId, snapshotOrdinal, snapshotOrdinalPublicTrust)

    forall(gen) {
      case (trust, seedlistEntries, peerId, snapshotOrdinal, snapshotOrdinalPublicTrust) =>
        for {
          ts <- mkTrustStorage(trust, seedlistEntries)

          _ <- ts.updateNext(snapshotOrdinal)
          _ <- ts.updateNext(peerId, snapshotOrdinalPublicTrust)
          _ <- ts.updateCurrent(snapshotOrdinal.plus(config.ordinalTrustUpdateDelay))

          routes <- mkPublicRoutes(ts)

          expected <- ts.getBiasedSeedlistOrdinalPeerLabels.map(_.asJson)
          testResult <- expectHttpBodyAndStatus(routes, req)(expected, Status.Ok)
        } yield testResult
    }
  }

}
