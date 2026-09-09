package io.constellationnetwork.node.shared.domain.snapshot.storage

import cats.data.NonEmptySet
import cats.effect.{IO, Ref}
import cats.kernel.Eq
import cats.syntax.all._

import scala.collection.immutable.SortedSet

import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.snapshot.Snapshot
import io.constellationnetwork.schema.{BlockAsActiveTip, SnapshotOrdinal, SnapshotTips}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}
import io.constellationnetwork.security.{Hashed, Hasher}

import weaver.SimpleIOSuite

object SnapshotStorageSuite extends SimpleIOSuite {

  private final case class TestSnapshot(
    ordinal: SnapshotOrdinal,
    lastSnapshotHash: Hash,
    height: Height = Height.MinValue,
    subHeight: SubHeight = SubHeight.MinValue,
    blocks: SortedSet[BlockAsActiveTip] = SortedSet.empty,
    tips: SnapshotTips = SnapshotTips(SortedSet.empty, SortedSet.empty),
    epochProgress: EpochProgress = EpochProgress.MinValue
  ) extends Snapshot

  private implicit val testSnapshotEq: Eq[TestSnapshot] = Eq.fromUniversalEquals

  private def signed[A](value: A, signer: String, signature: String): Signed[A] =
    Signed(
      value,
      NonEmptySet.one(SignatureProof(Id(Hex(signer)), Signature(Hex(signature))))
    )

  pureTest("exact install rejects a same-value artifact with different randomized proof bytes") {
    val expected = signed("artifact", "01", "aa")
    val stored = signed("artifact", "01", "bb")

    expect(!ExactSnapshotStorage.exactHeadMatches(expected, "context", Some(stored -> "context")))
  }

  pureTest("exact install rejects a different stored context") {
    val expected = signed("artifact", "01", "aa")

    expect(!ExactSnapshotStorage.exactHeadMatches(expected, "context", Some(expected -> "other-context")))
  }

  pureTest("exact install accepts only the complete expected artifact envelope and context") {
    val expected = signed("artifact", "01", "aa")

    expect(ExactSnapshotStorage.exactHeadMatches(expected, "context", Some(expected -> "context")))
  }

  test("validated recovery replaces a same-value head with different randomized proof bytes") {
    val value = TestSnapshot(SnapshotOrdinal.MinValue, Hash.empty)
    val expected = signed(value, "01", "aa")
    val stale = signed(value, "01", "bb")

    recoveryStorage(stale -> "context", prependResult = true).flatMap {
      case (storage, recoverySets) =>
        implicit val unusedHasher: Hasher[IO] = null.asInstanceOf[Hasher[IO]]

        ExactSnapshotStorage.installExactForRecovery(storage, expected, "context").flatMap { installed =>
          (storage.head, recoverySets.get).mapN { (head, sets) =>
            expect.all(installed, sets === 1, ExactSnapshotStorage.exactHeadMatches(expected, "context", head))
          }
        }
    }
  }

  test("validated recovery replaces a conflicting local head") {
    val expected = signed(TestSnapshot(SnapshotOrdinal.MinValue, Hash.empty), "01", "aa")
    val conflicting = signed(TestSnapshot(SnapshotOrdinal.MinValue, Hash("other-parent")), "01", "bb")

    recoveryStorage(conflicting -> "stale-context", prependResult = false).flatMap {
      case (storage, recoverySets) =>
        implicit val unusedHasher: Hasher[IO] = null.asInstanceOf[Hasher[IO]]

        ExactSnapshotStorage.installExactForRecovery(storage, expected, "context").flatMap { installed =>
          (storage.head, recoverySets.get).mapN { (head, sets) =>
            expect.all(installed, sets === 1, ExactSnapshotStorage.exactHeadMatches(expected, "context", head))
          }
        }
    }
  }

  test("canonical-suffix recovery never skips cleanup when the in-memory head already matches") {
    val expected = signed(TestSnapshot(SnapshotOrdinal.MinValue, Hash.empty), "01", "aa")

    recoveryStorage(expected -> "context", prependResult = true).flatMap {
      case (storage, recoverySets) =>
        implicit val unusedHasher: Hasher[IO] = null.asInstanceOf[Hasher[IO]]

        Ref.of[IO, Int](0).flatMap { cleanups =>
          ExactSnapshotStorage
            .installCanonicalSuffixForRecovery(storage, expected, "context", cleanups.update(_ + 1))
            .flatMap { installed =>
              (storage.head, recoverySets.get, cleanups.get).mapN { (head, sets, cleanupCount) =>
                expect.all(
                  installed,
                  sets === 1,
                  cleanupCount === 1,
                  ExactSnapshotStorage.exactHeadMatches(expected, "context", head)
                )
              }
            }
        }
    }
  }

  private def recoveryStorage(
    initial: (Signed[TestSnapshot], String),
    prependResult: Boolean
  ): IO[(SnapshotStorage[IO, TestSnapshot, String], Ref[IO, Int])] =
    (Ref.of[IO, Option[(Signed[TestSnapshot], String)]](initial.some), Ref.of[IO, Int](0)).mapN { (headRef, recoverySets) =>
      val storage = new SnapshotStorage[IO, TestSnapshot, String] {
        def prepend(snapshot: Signed[TestSnapshot], state: String)(implicit hasher: Hasher[IO]): IO[Boolean] =
          prependResult.pure[IO]

        def head: IO[Option[(Signed[TestSnapshot], String)]] = headRef.get
        def headSnapshot: IO[Option[Signed[TestSnapshot]]] = headRef.get.map(_.map(_._1))
        def get(ordinal: SnapshotOrdinal): IO[Option[Signed[TestSnapshot]]] = none.pure[IO]
        def getHashed(ordinal: SnapshotOrdinal)(implicit hasher: Hasher[IO]): IO[Option[Hashed[TestSnapshot]]] =
          none.pure[IO]
        def get(hash: Hash): IO[Option[Signed[TestSnapshot]]] = none.pure[IO]
        def getHash(ordinal: SnapshotOrdinal)(implicit hasher: Hasher[IO]): IO[Option[Hash]] = none.pure[IO]
        def setHeadForRecovery(snapshot: Signed[TestSnapshot], state: String)(implicit hasher: Hasher[IO]): IO[Unit] =
          recoverySets.update(_ + 1) >> headRef.set((snapshot -> state).some)
      }

      storage -> recoverySets
    }
}
