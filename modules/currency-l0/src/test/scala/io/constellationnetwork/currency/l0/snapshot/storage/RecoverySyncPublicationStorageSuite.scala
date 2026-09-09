package io.constellationnetwork.currency.l0.snapshot.storage

import cats.data.NonEmptySet
import cats.effect.IO
import cats.syntax.all._

import scala.collection.immutable.SortedSet

import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotStateProof, SnapshotFee}
import io.constellationnetwork.currency.schema.globalSnapshotSync.{GlobalSnapshotSync, GlobalSnapshotSyncOrdinal}
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.infrastructure.snapshot.RecoveryGlobalSnapshotSync.ResetInheritedMultiPeerView
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.LastSentGlobalSnapshotSyncStorage.RequiredRecoveryRefresh
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.cluster.SessionToken
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.generation.Generation
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.{SnapshotOrdinal, SnapshotTips}
import io.constellationnetwork.security.hash.{Hash, ProofsHash}
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}
import io.constellationnetwork.security.{Hashed, Hasher}
import io.constellationnetwork.statechannel.StateChannelSnapshotBinary

import eu.timepit.refined.types.numeric.PosLong
import fs2.io.file.Files
import weaver.SimpleIOSuite

object RecoverySyncPublicationStorageSuite extends SimpleIOSuite {

  private val proof = SignatureProof(Id(Hex("peer")), Signature(Hex("signature")))

  private def signed[A](value: A): Signed[A] = Signed(value, NonEmptySet.one(proof))

  private def artifact(ordinal: Long, hash: String): Hashed[CurrencyIncrementalSnapshot] = {
    val value = CurrencyIncrementalSnapshot(
      ordinal = SnapshotOrdinal.unsafeApply(ordinal),
      height = Height.MinValue,
      subHeight = SubHeight.MinValue,
      lastSnapshotHash = Hash.empty,
      blocks = SortedSet.empty,
      rewards = SortedSet.empty,
      tips = SnapshotTips(SortedSet.empty, SortedSet.empty),
      stateProof = CurrencySnapshotStateProof(Hash.empty, Hash.empty, None, None, None, None, None, None, None),
      epochProgress = EpochProgress.MinValue,
      dataApplication = None,
      messages = None,
      globalSnapshotSyncs = None,
      feeTransactions = None,
      artifacts = None,
      allowSpendBlocks = None,
      tokenLockBlocks = None,
      globalSyncView = None
    )

    Hashed(signed(value), Hash(hash), ProofsHash(s"$hash-proofs"))
  }

  private def binary(discriminator: Byte)(implicit hasher: Hasher[IO]): IO[Hashed[StateChannelSnapshotBinary]] =
    signed(StateChannelSnapshotBinary(Hash.empty, Array[Byte](discriminator, 2, 3), SnapshotFee.MinValue)).toHashed

  private val required = RequiredRecoveryRefresh(
    signed(
      GlobalSnapshotSync(
        GlobalSnapshotSyncOrdinal.MinValue,
        SnapshotOrdinal.unsafeApply(100L),
        Hash("global-100"),
        SessionToken(Generation(PosLong.unsafeFrom(2L)))
      )
    ),
    ResetInheritedMultiPeerView,
    SnapshotOrdinal.unsafeApply(147L)
  )

  test("prepared recovery binary is not publishable until exact local Currency commit is reconciled") {
    Files[IO].tempDirectory.use { directory =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
        storage <- RecoverySyncPublicationStorage.make[IO](directory)
        currencyArtifact = artifact(11L, "currency-11")
        recoveryBinary <- binary(11)
        prepared <- storage.prepare(required, recoveryBinary, currencyArtifact)
        reconciled <- storage.reconcilePrepared { ordinal =>
          Option.when(ordinal === currencyArtifact.ordinal)(currencyArtifact).pure[IO]
        }
        reloaded <- RecoverySyncPublicationStorage.make[IO](directory)
        persisted <- reloaded.get
      } yield
        expect(!prepared.locallyCommitted) &&
          expect(reconciled.exists(_.locallyCommitted)) &&
          expect(persisted.exists(_.locallyCommitted)) &&
          expect.same(Some(recoveryBinary.hash), persisted.map(_.binaryHash))
    }
  }

  test("crash before local Currency commit discards only the non-publishable intent") {
    Files[IO].tempDirectory.use { directory =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
        storage <- RecoverySyncPublicationStorage.make[IO](directory)
        recoveryBinary <- binary(12)
        _ <- storage.prepare(required, recoveryBinary, artifact(12L, "currency-absent"))
        reconciled <- storage.reconcilePrepared(_ => none.pure[IO])
        reloaded <- RecoverySyncPublicationStorage.make[IO](directory)
        persisted <- reloaded.get
      } yield expect(reconciled.isEmpty) && expect(persisted.isEmpty)
    }
  }

  test("a conflicting local artifact fails closed instead of publishing the prepared binary") {
    Files[IO].tempDirectory.use { directory =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
        storage <- RecoverySyncPublicationStorage.make[IO](directory)
        recoveryBinary <- binary(13)
        _ <- storage.prepare(required, recoveryBinary, artifact(13L, "currency-expected"))
        result <- storage.reconcilePrepared(_ => artifact(13L, "currency-other").some.pure[IO]).attempt
      } yield expect(result.swap.exists(_.isInstanceOf[RecoverySyncPublicationStorage.LocalCurrencyArtifactMismatch]))
    }
  }

  test("only exact canonical GL0 confirmation clears a committed publication") {
    Files[IO].tempDirectory.use { directory =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
        storage <- RecoverySyncPublicationStorage.make[IO](directory)
        recoveryBinary <- binary(14)
        _ <- storage.prepare(required, recoveryBinary, artifact(14L, "currency-confirm"))
        _ <- storage.markLocallyCommitted(recoveryBinary.hash)
        wrong <- storage.confirm(Set(Hash("other")))
        stillPresent <- storage.get
        exact <- storage.confirm(Set(recoveryBinary.hash))
        cleared <- storage.get
        reloaded <- RecoverySyncPublicationStorage.make[IO](directory)
        persisted <- reloaded.get
      } yield
        expect(wrong.isEmpty) &&
          expect(stillPresent.nonEmpty) &&
          expect(exact.exists(_.binaryHash === recoveryBinary.hash)) &&
          expect(cleared.isEmpty) &&
          expect(persisted.isEmpty)
    }
  }

  test("canonical GL0 observation cannot clear a non-publishable prepared intent") {
    Files[IO].tempDirectory.use { directory =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
        storage <- RecoverySyncPublicationStorage.make[IO](directory)
        recoveryBinary <- binary(15)
        _ <- storage.prepare(required, recoveryBinary, artifact(15L, "currency-prepared"))
        confirmed <- storage.confirm(Set(recoveryBinary.hash))
        expired <- storage.expireAt(SnapshotOrdinal.unsafeApply(required.validThroughGlobalParent.value.value + 1L))
        retained <- storage.get
      } yield
        expect(confirmed.isEmpty) &&
          expect(expired.isEmpty) &&
          expect(retained.exists(publication => !publication.locallyCommitted && !publication.expired))
    }
  }

  test("a committed publication expires only after its inclusive deadline and exact confirmation still clears its receipt") {
    Files[IO].tempDirectory.use { directory =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
        storage <- RecoverySyncPublicationStorage.make[IO](directory)
        recoveryBinary <- binary(16)
        _ <- storage.prepare(required, recoveryBinary, artifact(16L, "currency-expiry"))
        _ <- storage.markLocallyCommitted(recoveryBinary.hash)
        atDeadline <- storage.expireAt(required.validThroughGlobalParent)
        afterDeadline <- storage.expireAt(SnapshotOrdinal.unsafeApply(required.validThroughGlobalParent.value.value + 1L))
        persistedExpired <- RecoverySyncPublicationStorage.make[IO](directory).flatMap(_.get)
        confirmed <- storage.confirm(Set(recoveryBinary.hash))
        cleared <- RecoverySyncPublicationStorage.make[IO](directory).flatMap(_.get)
      } yield
        expect(atDeadline.isEmpty) &&
          expect(afterDeadline.exists(_.expired)) &&
          expect(persistedExpired.exists(_.expired)) &&
          expect(confirmed.exists(_.binaryHash === recoveryBinary.hash)) &&
          expect(cleared.isEmpty)
    }
  }

  test("a newly authorized rollback discards a stale recovery receipt regardless of commit state") {
    List(false, true).traverse { committed =>
      Files[IO].tempDirectory.use { directory =>
        for {
          implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
          implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
          storage <- RecoverySyncPublicationStorage.make[IO](directory)
          recoveryBinary <- binary(if (committed) 17 else 18)
          _ <- storage.prepare(required, recoveryBinary, artifact(17L, "superseded-currency"))
          _ <- storage.markLocallyCommitted(recoveryBinary.hash).whenA(committed)
          discarded <- storage.discardForCanonicalReplacement
          current <- storage.get
          persisted <- RecoverySyncPublicationStorage.make[IO](directory).flatMap(_.get)
        } yield expect.all(discarded.exists(_.binaryHash === recoveryBinary.hash), current.isEmpty, persisted.isEmpty)
      }
    }.map(_.combineAll)
  }
}
