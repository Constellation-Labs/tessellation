package io.constellationnetwork.dag.l0.infrastructure.trust

import cats.effect.{IO, Resource}
import cats.syntax.applicative._
import cats.syntax.eq._
import cats.syntax.option._

import io.constellationnetwork.dag.l0.infrastructure.trust.generators.genPeerLabel
import io.constellationnetwork.dag.l0.infrastructure.trust.storage.TrustStorage
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.config.types.TrustStorageConfig
import io.constellationnetwork.node.shared.domain.trust.storage._
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.generation.Generation
import io.constellationnetwork.schema.generators.peerIdGen
import io.constellationnetwork.schema.gossip._
import io.constellationnetwork.schema.trust.{PublicTrust, SnapshotOrdinalPublicTrust}
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import io.circe.syntax.EncoderOps
import org.scalacheck.Gen
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object HandlerSuite extends MutableIOSuite with Checkers {
  override type Res = KryoSerializer[IO]

  override def sharedResource: Resource[IO, Res] = KryoSerializer.forAsync[IO](sharedKryoRegistrar)

  def mkTrustStorage(trust: TrustMap = TrustMap.empty): F[TrustStorage[F]] = {
    val config = TrustStorageConfig(
      ordinalTrustUpdateInterval = 1000L,
      ordinalTrustUpdateDelay = 500L,
      seedlistInputBias = 0.7,
      seedlistOutputBias = 0.5
    )

    TrustStorage.make(trust, config, none)
  }

  test("rumor handler updates the trust storage") {
    val gen = for {
      peerId1 <- peerIdGen
      peerId2 <- peerIdGen
      trust <- Gen.mapOfN(4, genPeerLabel)
    } yield (peerId1, peerId2, trust)

    forall(gen) {
      case (peerId1, peerId2, trust) =>
        for {
          storage <- mkTrustStorage()
          handler <- handler.trustHandler(storage).pure
          publicTrust = PublicTrust(trust)
          rawRumor = PeerRumorRaw(
            peerId1,
            Ordinal(Generation.MinValue, Counter.MinValue),
            publicTrust.asJson,
            ContentType.of[PublicTrust]
          )
          firstTrust <- storage.getTrust

          _ <- handler.run((rawRumor, peerId2)).getOrElseF(IO.raiseError(new Exception("failed to handle rumor")))

          actual <- storage.getTrust
          expected = TrustMap.empty.copy(peerLabels = PublicTrustMap(Map(peerId1 -> PublicTrust(trust))))
        } yield
          expect.all(
            firstTrust.isEmpty,
            expected === actual
          )
    }
  }

  test("ordinal rumor handler updates the trust storage") {
    val gen = for {
      peerId1 <- peerIdGen
      peerId2 <- peerIdGen
      trust <- Gen.mapOfN(4, genPeerLabel)
    } yield (peerId1, peerId2, trust)

    forall(gen) {
      case (peerId1, peerId2, trust) =>
        for {
          storage <- mkTrustStorage()
          handler <- handler.ordinalTrustHandler(storage).pure
          publicTrust = SnapshotOrdinalPublicTrust(SnapshotOrdinal(1000L), PublicTrust(trust))
          rawRumor = PeerRumorRaw(
            peerId1,
            Ordinal(Generation.MinValue, Counter.MinValue),
            publicTrust.asJson,
            ContentType.of[SnapshotOrdinalPublicTrust]
          )
          firstTrust <- storage.getNextOrdinalTrust

          _ <- handler.run((rawRumor, peerId2)).getOrElseF(IO.raiseError(new Exception("failed to handle rumor")))

          actual <- storage.getNextOrdinalTrust
          expected = OrdinalTrustMap.empty
            .copy(ordinal = SnapshotOrdinal(1000L), peerLabels = PublicTrustMap(Map(peerId1 -> PublicTrust(trust))))
        } yield
          expect.all(
            OrdinalTrustMap.empty.copy(ordinal = SnapshotOrdinal(1000L)) === firstTrust,
            expected === actual
          )
    }
  }
}
