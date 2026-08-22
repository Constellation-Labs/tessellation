package io.constellationnetwork.node.shared.infrastructure.snapshot.storage

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.{IO, Ref}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.collection.mutable.ListBuffer

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.config.types.LastGlobalSnapshotsSyncConfig
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.key.ops._
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}
import weaver.SimpleIOSuite

object LastNGlobalSnapshotStorageInitializationSuite extends SimpleIOSuite {

  private implicit val stateProofSelector: GlobalStateProofSelector =
    GlobalStateProofSelector(SnapshotOrdinal(NonNegLong(Long.MaxValue)))

  private val info =
    GlobalSnapshotInfo(
      SortedMap.empty,
      SortedMap.empty,
      SortedMap.empty,
      SortedMap.empty,
      SortedMap.empty,
      None,
      None,
      None,
      None,
      None,
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty)
    )

  private def snapshot(
    ordinal: SnapshotOrdinal,
    parentHash: Hash,
    keyPair: java.security.KeyPair
  )(
    implicit hasher: Hasher[IO],
    serializer: JsonSerializer[IO],
    securityProvider: SecurityProvider[IO]
  ): IO[Hashed[GlobalIncrementalSnapshot]] =
    info.stateProof[IO](ordinal).flatMap { stateProof =>
      Signed
        .forAsyncHasher[IO, GlobalIncrementalSnapshot](
          GlobalIncrementalSnapshot(
            ordinal,
            // `Validator.isNextSnapshot` requires every ordinal transition to advance either
            // height or sub-height. Give the synthetic chain the same one-height-per-ordinal
            // shape used by ordinary incremental snapshots; keeping both values at MinValue
            // would create a hash-linked but semantically non-contiguous fixture.
            Height(ordinal.value),
            SubHeight.MinValue,
            parentHash,
            SortedSet.empty,
            SortedMap.empty,
            SortedSet.empty,
            None,
            EpochProgress.MinValue,
            NonEmptyList.one(PeerId.fromId(keyPair.getPublic.toId)),
            SnapshotTips(SortedSet.empty, SortedSet.empty),
            stateProof,
            Some(SortedSet.empty),
            Some(SortedSet.empty),
            Some(SortedMap.empty),
            Some(SortedMap.empty),
            Some(SortedSet.empty),
            Some(SortedMap.empty),
            Some(SortedMap.empty),
            Some(SortedMap.empty),
            Some(SortedMap.empty)
          ),
          keyPair
        )
        .flatMap(_.toHashed[IO])
    }

  private def chain(
    first: Long,
    last: Long,
    keyPair: java.security.KeyPair
  )(
    implicit hasher: Hasher[IO],
    serializer: JsonSerializer[IO],
    securityProvider: SecurityProvider[IO]
  ): IO[Map[SnapshotOrdinal, Hashed[GlobalIncrementalSnapshot]]] =
    (first to last).toList
      .foldLeftM((Hash.empty, Map.empty[SnapshotOrdinal, Hashed[GlobalIncrementalSnapshot]])) {
        case ((parentHash, acc), value) =>
          val ordinal = SnapshotOrdinal.unsafeApply(value)
          snapshot(ordinal, parentHash, keyPair).map(hashed => (hashed.hash, acc.updated(ordinal, hashed)))
      }
      .map(_._2)

  test("a failed required-window fetch leaves initialization retryable in the same process") {
    SecurityProvider.forAsync[IO].use { implicit securityProvider =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
        implicit0(hasherSelector: HasherSelector[IO]) = HasherSelector.forSyncAlwaysCurrent(hasher)
        keyPair <- KeyPairGenerator.makeKeyPair[IO]
        snapshots <- chain(50L, 100L, keyPair)
        storage <- LastNGlobalSnapshotStorage.make[IO](LastGlobalSnapshotsSyncConfig(NonNegLong(2L), PosInt(50)))
        parent = snapshots(SnapshotOrdinal.unsafeApply(100L))
        failTargetOnce <- Ref.of[IO, Boolean](true)
        fetcher = new GlobalL0Service[IO] {
          def pullLatestSnapshot: IO[LatestSnapshotTuple] = ???
          def pullLatestSnapshotFromRandomPeer: IO[LatestSnapshotTuple] = ???
          def pullLatestSnapshotIfNewer(localOrdinal: SnapshotOrdinal, localHash: Hash): IO[Option[LatestSnapshotTuple]] = ???
          def queryLatestEpochProgress: IO[Option[EpochProgress]] = ???
          def pullGlobalSnapshots: IO[Either[LatestSnapshotTuple, List[Hashed[GlobalIncrementalSnapshot]]]] = ???
          def pullGlobalSnapshots(ordinal: SnapshotOrdinal): IO[Either[LatestSnapshotTuple, List[Hashed[GlobalIncrementalSnapshot]]]] = ???
          def pullGlobalSnapshot(ordinal: SnapshotOrdinal): IO[Option[Hashed[GlobalIncrementalSnapshot]]] =
            if (ordinal === SnapshotOrdinal.unsafeApply(98L))
              failTargetOnce.getAndSet(false).flatMap {
                case true  => none[Hashed[GlobalIncrementalSnapshot]].pure[IO]
                case false => snapshots.get(ordinal).pure[IO]
              }
            else snapshots.get(ordinal).pure[IO]
          def pullGlobalSnapshot(hash: Hash): IO[Option[Hashed[GlobalIncrementalSnapshot]]] = ???
        }
        first <- storage.setInitialFetchingGL0(parent, info, fetcher.asLeft.some, none).attempt
        afterFailure <- storage.getCombined
        _ <- storage.setInitialFetchingGL0(parent, info, fetcher.asLeft.some, none)
        afterRetry <- storage.getCombined
        retained <- storage.getLastN
      } yield
        expect(first.isLeft) &&
          expect(afterFailure.isEmpty) &&
          expect(afterRetry.exists(_._1.hash === parent.hash)) &&
          expect(retained.exists(_.ordinal === SnapshotOrdinal.unsafeApply(98L)))
    }
  }

  test("a direct peer-fetch callback fills the required recovery window without a wrapped fetcher") {
    SecurityProvider.forAsync[IO].use { implicit securityProvider =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
        implicit0(hasherSelector: HasherSelector[IO]) = HasherSelector.forSyncAlwaysCurrent(hasher)
        keyPair <- KeyPairGenerator.makeKeyPair[IO]
        snapshots <- chain(98L, 100L, keyPair)
        storage <- LastNGlobalSnapshotStorage.make[IO](LastGlobalSnapshotsSyncConfig(NonNegLong(2L), PosInt(2)))
        parent = snapshots(SnapshotOrdinal.unsafeApply(100L))
        fetched <- Ref.of[IO, Set[SnapshotOrdinal]](Set.empty)
        fetch = (_: Option[Hash], ordinal: SnapshotOrdinal) =>
          fetched.update(_ + ordinal) >> snapshots.get(ordinal).liftTo[IO](new IllegalStateException(s"missing $ordinal")).map(_.signed)
        _ <- storage.setInitialFetchingGL0(parent, info, none, fetch.some)
        requested <- fetched.get
        retained <- storage.getLastN
      } yield
        expect.same(
          Set(SnapshotOrdinal.unsafeApply(98L), SnapshotOrdinal.unsafeApply(99L)),
          requested
        ) &&
          expect.same(
            Set(SnapshotOrdinal.unsafeApply(98L), SnapshotOrdinal.unsafeApply(99L), SnapshotOrdinal.unsafeApply(100L)),
            retained.map(_.ordinal).toSet
          )
    }
  }

  test("genesis initialization does not ask the direct callback to fetch its supplied parent") {
    SecurityProvider.forAsync[IO].use { implicit securityProvider =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
        implicit0(hasherSelector: HasherSelector[IO]) = HasherSelector.forSyncAlwaysCurrent(hasher)
        storage <- LastNGlobalSnapshotStorage.make[IO](LastGlobalSnapshotsSyncConfig(NonNegLong(2L), PosInt(2)))
        genesisOrdinal = SnapshotOrdinal.unsafeApply(1L)
        keyPair <- KeyPairGenerator.makeKeyPair[IO]
        genesis <- snapshot(genesisOrdinal, Hash.empty, keyPair)
        fetches <- Ref.of[IO, Int](0)
        fetch = (_: Option[Hash], _: SnapshotOrdinal) =>
          fetches.update(_ + 1) >> IO
            .raiseError[Signed[GlobalIncrementalSnapshot]](new IllegalStateException("no peer snapshot at genesis"))
        result <- storage.setInitialFetchingGL0(genesis, info, none, fetch.some).attempt
        requested <- fetches.get
        retained <- storage.getLastN
      } yield
        expect(result.isRight) &&
          expect.same(0, requested) &&
          expect.same(List(genesisOrdinal), retained.map(_.ordinal))
    }
  }

  test("a fetched value with the wrong expected hash fails before either index is installed") {
    SecurityProvider.forAsync[IO].use { implicit securityProvider =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
        implicit0(hasherSelector: HasherSelector[IO]) = HasherSelector.forSyncAlwaysCurrent(hasher)
        canonicalKey <- KeyPairGenerator.makeKeyPair[IO]
        otherKey <- KeyPairGenerator.makeKeyPair[IO]
        canonical <- chain(98L, 100L, canonicalKey)
        wrong <- chain(98L, 99L, otherKey)
        storage <- LastNGlobalSnapshotStorage.make[IO](LastGlobalSnapshotsSyncConfig(NonNegLong(1L), PosInt(2)))
        fetch = (_: Option[Hash], ordinal: SnapshotOrdinal) =>
          wrong.get(ordinal).liftTo[IO](new IllegalStateException(s"missing $ordinal")).map(_.signed)
        result <- storage
          .setInitialFetchingGL0(canonical(SnapshotOrdinal.unsafeApply(100L)), info, none, fetch.some)
          .attempt
        combined <- storage.getCombined
        retained <- storage.getLastN
      } yield
        expect(result.left.exists(_.getMessage.contains("hash mismatch"))) &&
          expect(combined.isEmpty) &&
          expect(retained.isEmpty)
    }
  }

  test("an invalid predecessor signature fails before either index is installed") {
    SecurityProvider.forAsync[IO].use { implicit securityProvider =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
        implicit0(hasherSelector: HasherSelector[IO]) = HasherSelector.forSyncAlwaysCurrent(hasher)
        keyPair <- KeyPairGenerator.makeKeyPair[IO]
        original <- snapshot(SnapshotOrdinal.unsafeApply(99L), Hash.empty, keyPair)
        tamperedSigned = original.signed.copy(value = original.signed.value.copy(lastSnapshotHash = Hash.fromBytes(Array[Byte](1))))
        tampered <- tamperedSigned.toHashed[IO]
        child <- snapshot(SnapshotOrdinal.unsafeApply(100L), tampered.hash, keyPair)
        storage <- LastNGlobalSnapshotStorage.make[IO](LastGlobalSnapshotsSyncConfig(NonNegLong(1L), PosInt(1)))
        fetch = (_: Option[Hash], _: SnapshotOrdinal) => tamperedSigned.pure[IO]
        result <- storage.setInitialFetchingGL0(child, info, none, fetch.some).attempt
        combined <- storage.getCombined
        retained <- storage.getLastN
      } yield
        expect(result.left.exists(_.getMessage.contains("invalid signatures"))) &&
          expect(combined.isEmpty) &&
          expect(retained.isEmpty)
    }
  }

  test("duplicate signer identities are rejected independently of cryptographic validity") {
    SecurityProvider.forAsync[IO].use { implicit securityProvider =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
        implicit0(hasherSelector: HasherSelector[IO]) = HasherSelector.forSyncAlwaysCurrent(hasher)
        keyPair <- KeyPairGenerator.makeKeyPair[IO]
        predecessor <- snapshot(SnapshotOrdinal.unsafeApply(99L), Hash.empty, keyPair)
        originalProof = predecessor.signed.proofs.head
        duplicateIdentity = SignatureProof(originalProof.id, Signature(Hex("00")))
        duplicateSigned = predecessor.signed.copy(
          proofs = NonEmptySet.fromSetUnsafe(predecessor.signed.proofs.toSortedSet + duplicateIdentity)
        )
        child <- snapshot(SnapshotOrdinal.unsafeApply(100L), predecessor.hash, keyPair)
        storage <- LastNGlobalSnapshotStorage.make[IO](LastGlobalSnapshotsSyncConfig(NonNegLong(1L), PosInt(1)))
        fetch = (_: Option[Hash], _: SnapshotOrdinal) => duplicateSigned.pure[IO]
        result <- storage.setInitialFetchingGL0(child, info, none, fetch.some).attempt
        combined <- storage.getCombined
        retained <- storage.getLastN
      } yield
        expect(result.left.exists(_.getMessage.contains("duplicate signers"))) &&
          expect(combined.isEmpty) &&
          expect(retained.isEmpty)
    }
  }

  test("a signer outside the configured upstream seedlist is rejected atomically") {
    SecurityProvider.forAsync[IO].use { implicit securityProvider =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
        implicit0(hasherSelector: HasherSelector[IO]) = HasherSelector.forSyncAlwaysCurrent(hasher)
        signer <- KeyPairGenerator.makeKeyPair[IO]
        allowed <- KeyPairGenerator.makeKeyPair[IO]
        snapshots <- chain(99L, 100L, signer)
        storage <- LastNGlobalSnapshotStorage.make[IO](
          LastGlobalSnapshotsSyncConfig(NonNegLong(1L), PosInt(1)),
          Set(PeerId.fromId(allowed.getPublic.toId)).some
        )
        fetch = (_: Option[Hash], ordinal: SnapshotOrdinal) =>
          snapshots.get(ordinal).liftTo[IO](new IllegalStateException(s"missing $ordinal")).map(_.signed)
        result <- storage
          .setInitialFetchingGL0(snapshots(SnapshotOrdinal.unsafeApply(100L)), info, none, fetch.some)
          .attempt
        combined <- storage.getCombined
        retained <- storage.getLastN
      } yield
        expect(result.left.exists(_.getMessage.contains("outside the configured seedlist"))) &&
          expect(combined.isEmpty) &&
          expect(retained.isEmpty)
    }
  }

  test("every snapshot is hashed and verified with the selector for its own historical ordinal") {
    SecurityProvider.forAsync[IO].use { implicit securityProvider =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
        selected = ListBuffer.empty[SnapshotOrdinal]
        implicit0(hasherSelector: HasherSelector[IO]) = new HasherSelector[IO] {
          def getForOrdinal(ordinal: SnapshotOrdinal): Hasher[IO] = {
            selected.synchronized(selected += ordinal)
            hasher
          }
          def getCurrent: Hasher[IO] =
            throw new IllegalStateException("LastN initialization must not use the current hasher implicitly")
        }
        keyPair <- KeyPairGenerator.makeKeyPair[IO]
        snapshots <- chain(98L, 100L, keyPair)
        storage <- LastNGlobalSnapshotStorage.make[IO](LastGlobalSnapshotsSyncConfig(NonNegLong(2L), PosInt(2)))
        fetch = (_: Option[Hash], ordinal: SnapshotOrdinal) =>
          snapshots.get(ordinal).liftTo[IO](new IllegalStateException(s"missing $ordinal")).map(_.signed)
        _ <- storage.setInitialFetchingGL0(snapshots(SnapshotOrdinal.unsafeApply(100L)), info, none, fetch.some)
      } yield
        expect.same(
          List(100L, 99L, 98L).map(SnapshotOrdinal.unsafeApply),
          selected.synchronized(selected.toList)
        )
    }
  }
}
