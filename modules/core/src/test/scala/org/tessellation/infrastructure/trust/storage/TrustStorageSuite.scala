package org.tessellation.infrastructure.trust.storage

import cats.implicits.catsStdShowForSet
import cats.kernel.Order
import cats.syntax.option._
import cats.syntax.partialOrder._

import scala.math.max

import org.tessellation.infrastructure.trust.generators._
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.generators.peerIdGen
import org.tessellation.schema.peer.PeerId._
import org.tessellation.schema.trust._
import org.tessellation.sdk.config.types.TrustStorageConfig
import org.tessellation.sdk.domain.seedlist.SeedlistEntry
import org.tessellation.sdk.domain.trust.storage._

import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.NonNegative
import monocle.Monocle.toAppliedFocusOps
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object TrustStorageSuite extends SimpleIOSuite with Checkers {

  def mkTrustStorage(trust: TrustMap = TrustMap.empty, seedlist: Option[Set[SeedlistEntry]] = none): F[TrustStorage[F]] = {
    val config = TrustStorageConfig(
      ordinalTrustUpdateInterval = 1000L,
      ordinalTrustUpdateDelay = 500L,
      seedlistInputBias = 0.7,
      seedlistOutputBias = 0.5
    )

    TrustStorage.make(trust, config, seedlist)
  }

  test("initial trust storage is empty") {
    for {
      store <- mkTrustStorage()
      trust <- store.getTrust
    } yield expect.eql(TrustMap.empty, trust)
  }

  test("internal trust storage is updated with trust labels") {
    val gen = Gen.listOfN(4, genPeerObservationAdjustmentUpdate).map(labels => PeerObservationAdjustmentUpdateBatch(labels))

    forall(gen) { labels =>
      for {
        store <- mkTrustStorage()

        _ <- store.updateTrust(labels)

        expected = labels.updates.map(update => update.id -> TrustInfo(trustLabel = update.trust.value.some)).toMap
        actual <- store.getTrust.map(_.trust)
      } yield expect.eql(expected, actual)
    }
  }

  test("internal trust storage is updated with predicted trusts") {
    val genSeedlistEntries = for {
      peerId <- peerIdGen
      score <- Gen.chooseNum(-1.0, 1.0)

      refinedScore = Refined.unsafeApply[Double, TrustValueRefinement](score)
    } yield SeedlistEntry(peerId, none, none, refinedScore.some)

    val gen = for {
      trust <- Gen.mapOfN(4, genPeerTrustInfo)
      publicTrust <- Gen.mapOfN(4, genPeerPublicTrust)
      seedlist <- Gen.containerOfN[Set, SeedlistEntry](1, genSeedlistEntries)
      selfPeerId <- peerIdGen
    } yield
      (
        TrustMap(trust, PublicTrustMap(publicTrust)),
        seedlist,
        selfPeerId
      )

    forall(gen) {
      case (trust, seedlist, selfPeerId) =>
        for {
          store <- mkTrustStorage(trust = trust, seedlist = seedlist.some)
          firstTrustMap <- store.getTrust.map(_.trust)

          _ <- store.updateTrustWithBiases(selfPeerId)

          secondTrustMap <- store.getTrust.map(_.trust)
        } yield
          expect.all(
            !firstTrustMap.contains(selfPeerId),
            secondTrustMap.contains(selfPeerId)
          )
    }
  }

  test("internal trust storage is updated with public trust") {
    val gen = for {
      peerId <- peerIdGen
      labels <- Gen.mapOfN(4, genPeerLabel)
    } yield (peerId, PublicTrust(labels))

    forall(gen) {
      case (peerId, publicTrust) =>
        for {
          store <- mkTrustStorage()

          _ <- store.updatePeerPublicTrustInfo(peerId, publicTrust)

          expected = PublicTrustMap(Map(peerId -> PublicTrust(publicTrust.labels)))
          actual <- store.getTrust.map(_.peerLabels)
        } yield expect.eql(expected, actual)
    }
  }

  test("initial current ordinal trust storage is empty") {
    for {
      store <- mkTrustStorage()
      trust <- store.getCurrentOrdinalTrust
    } yield expect.eql(OrdinalTrustMap.empty, trust)
  }

  test("initial next ordinal trust storage is empty except for the ordinal") {
    for {
      store <- mkTrustStorage()
      trust <- store.getNextOrdinalTrust
    } yield
      expect.all(
        SnapshotOrdinal(1000L) === trust.ordinal,
        PublicTrustMap.empty === trust.peerLabels
      )
  }

  test("next ordinal trust storage doesn't update from internal storage before expected ordinal has been reached") {
    val minOrdinalValue = 0L
    val maxOrdinalValue = 500L - 1L

    val gen = for {
      trustInfo <- Gen.mapOfN(4, genPeerTrustInfo)
      delta <- Gen.chooseNum(minOrdinalValue, maxOrdinalValue)
      ordinal = Refined.unsafeApply[Long, NonNegative](delta)
    } yield (TrustMap.empty.copy(trust = trustInfo), SnapshotOrdinal(ordinal))

    forall(gen) {
      case (trust, ordinal) =>
        for {
          store <- mkTrustStorage(trust)
          storedTrust <- store.getTrust

          maybeOrdinalPublicTrust <- store.updateNext(ordinal)

          next <- store.getNextOrdinalTrust
        } yield
          expect.all(
            trust === storedTrust,
            Order.gt(SnapshotOrdinal(1000L), ordinal),
            maybeOrdinalPublicTrust.isEmpty,
            next.trust.isEmpty,
            SnapshotOrdinal(1000L) === next.ordinal,
            PublicTrustMap.empty === next.peerLabels
          )
    }
  }

  test("next ordinal trust storage doesn't update from internal storage when the internal storage is empty") {
    val minOrdinalValue = 1000L + 1L
    val maxOrdinalValue = 1000L * 2L

    val gen = for {
      delta <- Gen.chooseNum(minOrdinalValue, maxOrdinalValue)
      ordinal = Refined.unsafeApply[Long, NonNegative](delta)
    } yield SnapshotOrdinal(ordinal)

    forall(gen) { ordinal =>
      for {
        store <- mkTrustStorage()

        maybeOrdinalPublicTrust <- store.updateNext(ordinal)

        expectedTrust <- store.getTrust
      } yield
        expect.all(
          Order.lt(SnapshotOrdinal(1000L), ordinal),
          expectedTrust.isEmpty,
          maybeOrdinalPublicTrust.isEmpty
        )
    }
  }

  test("next ordinal trust storage updates from internal storage when expected ordinal has been reached") {
    val minOrdinalValue = 1000L + 1L
    val maxOrdinalValue = 1000L * 2L

    val gen = for {
      trustInfo <- Gen.mapOfN(4, genPeerTrustInfo)
      delta <- Gen.chooseNum(minOrdinalValue, maxOrdinalValue)
      ordinal = Refined.unsafeApply[Long, NonNegative](delta)
    } yield (TrustMap.empty.copy(trust = trustInfo), SnapshotOrdinal(ordinal))

    forall(gen) {
      case (trust, ordinal) =>
        for {
          store <- mkTrustStorage(trust)

          maybeOrdinalPublicTrust <- store.updateNext(ordinal)

          expectedOrdinalPublicTrust = trust.toPublicTrust
          next <- store.getNextOrdinalTrust
        } yield
          expect.all(
            Order.lt(SnapshotOrdinal(1000L), ordinal),
            maybeOrdinalPublicTrust.map(_.ordinal) === SnapshotOrdinal(1000L).some,
            maybeOrdinalPublicTrust.map(_.labels) === expectedOrdinalPublicTrust.some,
            trust === next.trust
          )
    }
  }

  test(
    "current ordinal trust storage doesn't update from next ordinal trust storage before the delay expected ordinal has been reached"
  ) {
    val minOrdinalValue = 1000L
    val maxOrdinalValue = 1000L + 500L - 1L

    val gen = for {
      trustInfo <- Gen.mapOfN(4, genPeerTrustInfo)
      delta <- Gen.chooseNum(minOrdinalValue, maxOrdinalValue)
      ordinal = Refined.unsafeApply[Long, NonNegative](delta)
    } yield (TrustMap.empty.copy(trust = trustInfo), SnapshotOrdinal(ordinal))

    forall(gen) {
      case (trust, ordinal) =>
        for {
          store <- mkTrustStorage(trust)

          _ <- store.updateNext(ordinal)

          next <- store.getNextOrdinalTrust

          _ <- store.updateCurrent(ordinal)

          expectedCurrent <- store.getCurrentOrdinalTrust
        } yield
          expect.all(
            Order.gt(SnapshotOrdinal(1000L + 500L), ordinal),
            trust === next.trust,
            OrdinalTrustMap.empty === expectedCurrent
          )
    }
  }

  test(
    "current ordinal trust storage updates from next ordinal trust storage when the delay expected ordinal has been reached"
  ) {
    val minFirstOrdinalValue = 0L
    val maxFirstOrdinalValue = 1000L - 1L
    val minSecondOrdinalValue = 1000L + 500L
    val maxSecondOrdinalValue = (1000L * 2) - 1L

    val gen = for {
      trustInfo <- Gen.mapOfN(4, genPeerTrustInfo)

      firstDelta <- Gen.chooseNum(minFirstOrdinalValue, maxFirstOrdinalValue)
      firstOrdinal = Refined.unsafeApply[Long, NonNegative](firstDelta)

      secondDelta <- Gen.chooseNum(minSecondOrdinalValue, maxSecondOrdinalValue)
      secondOrdinal = Refined.unsafeApply[Long, NonNegative](secondDelta)
    } yield (TrustMap.empty.copy(trust = trustInfo), SnapshotOrdinal(firstOrdinal), SnapshotOrdinal(secondOrdinal))

    forall(gen) {
      case (trust, firstOrdinal, secondOrdinal) =>
        for {
          store <- mkTrustStorage(trust)

          _ <- store.updateNext(firstOrdinal)

          firstNext <- store.getNextOrdinalTrust

          _ <- store.updateCurrent(secondOrdinal)

          current <- store.getCurrentOrdinalTrust
          secondNext <- store.getNextOrdinalTrust
        } yield
          expect.all(
            Order.lteqv(SnapshotOrdinal(1000L + 500L), secondOrdinal),
            firstNext.trust === current.trust,
            SnapshotOrdinal(1000L) === current.ordinal,
            PublicTrustMap.empty === current.peerLabels,
            SnapshotOrdinal(Refined.unsafeApply[Long, NonNegative](1000L * 2)) === secondNext.ordinal,
            secondNext.trust.isEmpty
          )
    }
  }

  test(
    "next ordinal trust storage doesn't update from gossip when the gossip ordinal doesn't match the next ordinal"
  ) {
    val gen = for {
      trustInfo <- Gen.mapOfN(4, genPeerTrustInfo)
      peerId <- peerIdGen
    } yield (TrustMap.empty.copy(trust = trustInfo), peerId)

    forall(gen) {
      case (trust, peerId) =>
        for {
          store <- mkTrustStorage(trust)
          gossipOrdinal = SnapshotOrdinal(2000L)

          gossipOrdinalPublicTrust = SnapshotOrdinalPublicTrust(
            gossipOrdinal,
            trust.toPublicTrust
          )

          _ <- store.updateNext(peerId, gossipOrdinalPublicTrust)

          secondNext <- store.getNextOrdinalTrust
        } yield
          expect.all(
            SnapshotOrdinal(1000L) === secondNext.ordinal,
            secondNext.trust.isEmpty,
            PublicTrustMap.empty === secondNext.peerLabels
          )
    }
  }

  test(
    "next ordinal trust storage doesn't update from gossip when an entry already exists for the peer id"
  ) {
    val gen = Gen.mapOfN(4, genPeerTrustInfo)

    forall(gen) { trust =>
      for {
        store <- mkTrustStorage(TrustMap.empty.copy(trust = trust))
        gossipOrdinal = SnapshotOrdinal(1000L)

        _ <- store.updateNext(gossipOrdinal)

        (peerId, _) = trust.toList.head

        _ <- store.updateNext(
          peerId,
          SnapshotOrdinalPublicTrust(
            gossipOrdinal,
            PublicTrust(Map(peerId -> 1.0))
          )
        )

        firstNext <- store.getNextOrdinalTrust
        gossipPublicTrust = PublicTrust(Map(peerId -> 2.0))
        gossipOrdinalPublicTrust = SnapshotOrdinalPublicTrust(
          gossipOrdinal,
          gossipPublicTrust
        )

        _ <- store.updateNext(peerId, gossipOrdinalPublicTrust)

        secondNext <- store.getNextOrdinalTrust
      } yield
        expect.all(
          firstNext.peerLabels.value.contains(peerId),
          SnapshotOrdinal(1000L) === secondNext.ordinal,
          firstNext.trust === secondNext.trust,
          firstNext.peerLabels === secondNext.peerLabels
        )
    }
  }

  test(
    "next ordinal trust storage updates from gossip"
  ) {
    val gen = Gen.mapOfN(4, genPeerTrustInfo)

    forall(gen) { trust =>
      for {
        store <- mkTrustStorage(TrustMap.empty.copy(trust = trust))
        gossipOrdinal = SnapshotOrdinal(1000L)

        _ <- store.updateNext(gossipOrdinal)

        (peerId, _) = trust.toList.head
        firstNext <- store.getNextOrdinalTrust
        gossipPublicTrust = PublicTrust(Map(peerId -> 2.0))
        gossipOrdinalPublicTrust = SnapshotOrdinalPublicTrust(
          gossipOrdinal,
          gossipPublicTrust
        )

        _ <- store.updateNext(peerId, gossipOrdinalPublicTrust)

        secondNext <- store.getNextOrdinalTrust
      } yield
        expect.all(
          !firstNext.peerLabels.value.contains(peerId),
          SnapshotOrdinal(1000L) === secondNext.ordinal,
          firstNext.trust === secondNext.trust,
          secondNext.peerLabels.value.contains(peerId),
          secondNext.peerLabels.value.get(peerId).contains(gossipPublicTrust)
        )
    }
  }

  test("a minimum threshold is applied to public trust when it is retrieved") {
    val gen = for {
      trustInfo <- Gen.mapOfN(4, genPeerTrustInfo)
    } yield TrustMap.empty.copy(trust = trustInfo)

    forall(gen) {
      case trust =>
        for {
          store <- mkTrustStorage(trust)

          expected = trust.toPublicTrust
            .focus(_.labels)
            .modify(
              _.view
                .mapValues(max(0.5, _))
                .toMap
            )
          actual <- store.getPublicTrust
        } yield expect.eql(expected, actual)
    }
  }
}
