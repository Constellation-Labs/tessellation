package org.tessellation.infrastructure.trust.storage

import cats.effect.{IO, Ref}
import cats.syntax.applicative._
import cats.syntax.eq._
import cats.syntax.option._

import org.tessellation.ext.cats.syntax.next.catsSyntaxNext
import org.tessellation.infrastructure.trust.TrustInfoGenerator.{genPeerId, genPeerTrustInfo}
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.SnapshotOrdinal._
import org.tessellation.schema.generators.snapshotOrdinalGen
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.trust.{SnapshotOrdinalTrustInfo, TrustInfo}

import eu.timepit.refined.auto._
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object InMemorySnapshotOrdinalTrustStorageSuite extends SimpleIOSuite with Checkers {

  def getSnapshotOrdinal(ordinalRef: Ref[F, Option[SnapshotOrdinal]]): F[Option[SnapshotOrdinal]] = ordinalRef.get

  def mkExpectedTrustStorage(ordinal: SnapshotOrdinal, trustInfo: Map[PeerId, TrustInfo]): Map[PeerId, SnapshotOrdinalTrustInfo] =
    trustInfo.filterNot { case (_, trust) => trust.isEmpty }.view.mapValues { v =>
      SnapshotOrdinalTrustInfo(v, ordinal)
    }.toMap

  test("storage starts empty") {
    for {
      ordinalRef <- Ref[F].of(SnapshotOrdinal.MinValue.some)
      storage <- InMemorySnapshotOrdinalTrustStorage.make(getSnapshotOrdinal(ordinalRef))

      expected <- Map.empty[PeerId, SnapshotOrdinalTrustInfo].pure[F]
      actual <- storage.getTrust
    } yield expect.eql(expected, actual)
  }

  test("do not update storage if there is no ordinal") {
    for {
      ordinalRef <- Ref[F].of(none[SnapshotOrdinal])
      storage <- InMemorySnapshotOrdinalTrustStorage.make(getSnapshotOrdinal(ordinalRef))

      expected <- Map.empty[PeerId, SnapshotOrdinalTrustInfo].pure[F]
      actual <- storage.getTrust
    } yield expect.eql(expected, actual)
  }

  test("successfully update storage") {
    val gen = for {
      ordinal <- snapshotOrdinalGen
      trusts <- Gen.mapOfN(4, genPeerTrustInfo)
    } yield (ordinal, trusts)

    forall(gen) {
      case (ordinal, trust) =>
        for {
          ordinalRef <- Ref[F].of(ordinal.some)
          storage <- InMemorySnapshotOrdinalTrustStorage.make(getSnapshotOrdinal(ordinalRef))
          _ <- storage.update(trust)

          expected = mkExpectedTrustStorage(ordinal, trust)
          actual <- storage.getTrust
        } yield expect.eql(expected, actual)
    }
  }

  test("successfully update storage without empty trusts") {
    val gen = for {
      ordinal <- snapshotOrdinalGen
      trusts <- Gen.mapOfN(4, genPeerTrustInfo)
      peerId <- genPeerId
    } yield (ordinal, trusts, peerId)

    forall(gen) {
      case (ordinal, trust, peerId) =>
        for {
          ordinalRef <- Ref[F].of(ordinal.some)
          storage <- InMemorySnapshotOrdinalTrustStorage.make(getSnapshotOrdinal(ordinalRef))
          emptyTrust = List(peerId -> TrustInfo()).toMap
          augmentedTrust = trust ++ emptyTrust
          _ <- storage.update(augmentedTrust)

          expected = mkExpectedTrustStorage(ordinal, trust)
          actual <- storage.getTrust
        } yield expect.eql(expected, actual)
    }
  }

  test("update storage with older ordinal should not change existing data") {
    val gen = for {
      ordinal <- snapshotOrdinalGen
      trusts1 <- Gen.mapOfN(4, genPeerTrustInfo)
      trusts2 <- Gen.mapOfN(2, genPeerTrustInfo)
    } yield (ordinal, trusts1, trusts2)

    forall(gen) {
      case (ordinal, trust1, trust2) =>
        for {
          largerOrdinal <- ordinal.next.pure[F]
          ref <- Ref[IO].of(largerOrdinal.some)
          storage <- InMemorySnapshotOrdinalTrustStorage.make(getSnapshotOrdinal(ref))

          actualFirstOrdinal <- storage.update(trust1)
          actualFirstUpdate <- storage.getTrust
          expectedFirstUpdate = mkExpectedTrustStorage(largerOrdinal, trust1)

          _ <- ref.set(ordinal.some)

          actualSecondOrdinal <- storage.update(trust1 ++ trust2)
          secondUpdate <- storage.getTrust

          expectedSecondUpdate = expectedFirstUpdate ++ mkExpectedTrustStorage(ordinal, trust2)
          actualSecondUpdate = secondUpdate
        } yield
          expect.all(
            actualFirstOrdinal === largerOrdinal,
            actualFirstUpdate === expectedFirstUpdate,
            actualSecondOrdinal === ordinal,
            actualSecondUpdate === expectedSecondUpdate
          )
    }
  }

  test("update with later ordinal") {
    val gen = for {
      ordinal <- snapshotOrdinalGen
      trusts <- Gen.mapOfN(4, genPeerTrustInfo)
    } yield (ordinal, trusts)

    def updateTrust(trustInfo: TrustInfo) =
      trustInfo.copy(observationAdjustmentTrust = trustInfo.observationAdjustmentTrust.map(updateTrustValue).orElse(0.0.some))

    def updateTrustValue(value: Double): Double =
      if (value >= 0.9 || value <= -0.9) {
        0.0
      } else {
        value + 0.1
      }

    forall(gen) {
      case (ordinal, trust) =>
        for {
          ref <- Ref[F].of(ordinal.some)
          storage <- InMemorySnapshotOrdinalTrustStorage.make(getSnapshotOrdinal(ref))

          _ <- storage.update(trust)
          _ <- storage.getTrust

          biggerOrdinal = ordinal.plus(1L)
          _ <- ref.set(biggerOrdinal.some)
          trust2 = trust.view.mapValues(updateTrust).toMap

          secondOrdinal <- storage.update(trust2)
          secondUpdate <- storage.getTrust

          expectedTrust = mkExpectedTrustStorage(biggerOrdinal, trust2)
        } yield
          expect.all(
            secondOrdinal === biggerOrdinal,
            secondUpdate === expectedTrust
          )
    }
  }

  test("successfully update storage with new entries only if the ordinal is the same") {
    val gen = for {
      ordinal <- snapshotOrdinalGen
      trusts1 <- Gen.mapOfN(4, genPeerTrustInfo)
      trusts2 <- Gen.mapOfN(2, genPeerTrustInfo)
    } yield (ordinal, trusts1, trusts2)

    forall(gen) {
      case (ordinal, trust1, trust2) =>
        for {
          ordinalRef <- Ref[F].of(ordinal.some)
          storage <- InMemorySnapshotOrdinalTrustStorage.make(getSnapshotOrdinal(ordinalRef))

          firstOrdinal <- storage.update(trust1)
          firstActualTrust <- storage.getTrust

          secondOrdinal <- storage.update(trust2)
          secondActualTrust <- storage.getTrust

          firstExpectedTrust = mkExpectedTrustStorage(ordinal, trust1)
          secondExpectedTrust = mkExpectedTrustStorage(ordinal, trust1 ++ trust2)
        } yield
          expect.all(
            firstExpectedTrust === firstActualTrust,
            secondExpectedTrust === secondActualTrust,
            firstActualTrust =!= secondActualTrust,
            firstOrdinal === ordinal,
            secondOrdinal === ordinal
          )
    }
  }

}
