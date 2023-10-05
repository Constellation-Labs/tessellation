package org.tessellation.infrastructure.trust

import cats.effect.{IO, Resource}
import cats.syntax.applicative._
import cats.syntax.eq._

import org.tessellation.infrastructure.trust.generators.genPeerLabel
import org.tessellation.infrastructure.trust.storage.TrustStorage
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.generation.Generation
import org.tessellation.schema.generators.peerIdGen
import org.tessellation.schema.gossip._
import org.tessellation.schema.trust.{PublicTrust, SnapshotOrdinalPublicTrust}
import org.tessellation.sdk.config.types.TrustStorageConfig
import org.tessellation.sdk.domain.seedlist.SeedlistEntry
import org.tessellation.sdk.domain.trust.storage._
import org.tessellation.shared.sharedKryoRegistrar

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

    TrustStorage.make(trust, config, Set.empty[SeedlistEntry])
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
