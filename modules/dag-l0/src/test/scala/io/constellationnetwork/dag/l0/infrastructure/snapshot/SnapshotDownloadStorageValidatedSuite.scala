package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage._
import io.constellationnetwork.node.shared.nodeSharedKryoRegistrar
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.mpt.GlobalStateFieldId.LastStateChannelSnapshotHashes
import io.constellationnetwork.schema.mpt.PartitionNamespace.{EmptyNamespace, HypergraphNamespace}
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.mpt.MptRoot
import io.constellationnetwork.security.mpt.producer.{InMemoryMerklePatriciaProducer, StatefulMerklePatriciaProducer}
import io.constellationnetwork.security.signature.Signed

import better.files.File
import eu.timepit.refined.types.numeric.NonNegLong
import fs2.io.file.Path
import io.circe.Json
import weaver.MutableIOSuite

object SnapshotDownloadStorageValidatedSuite extends MutableIOSuite {

  implicit val globalStateProofSelector: GlobalStateProofSelector =
    GlobalStateProofSelector(SnapshotOrdinal(NonNegLong(0L)))

  type Res = (KryoSerializer[IO], JsonSerializer[IO], Hasher[IO], SecurityProvider[IO], Metrics[IO])

  override def sharedResource: Resource[IO, Res] = for {
    implicit0(kryoSerializer: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](nodeSharedKryoRegistrar)
    implicit0(jsonSerializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    securityProvider <- SecurityProvider.forAsync[IO]
    hasher = Hasher.forJson[IO]
    metrics <- Metrics.forAsync[IO](Seq.empty)
  } yield (kryoSerializer, jsonSerializer, hasher, securityProvider, metrics)

  private type MptWitness = (Map[io.constellationnetwork.security.hex.Hex, Vector[Byte]], Option[MptRoot], Option[SnapshotOrdinal])

  private def witness(producer: StatefulMerklePatriciaProducer[IO]): IO[MptWitness] =
    (
      producer.entries.map(_.view.mapValues(_.toVector).toMap),
      producer.getCurrentRootHash,
      producer.getLastBuiltOrdinal
    ).tupled

  test("readCombinedValidated never mutates the application MPT or self-heals invalid persisted files") { implicit res =>
    implicit val (kryoSerializer, jsonSerializer, hasher, securityProvider, metrics) = res
    implicit val hasherSelector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(hasher)

    val hashSelect = new HashSelect {
      def select(ordinal: SnapshotOrdinal): HashLogic = JsonHash
    }

    File.temporaryDirectory() { root =>
      def path(name: String): Path = Path((root / name).pathAsString)

      val ordinal = SnapshotOrdinal.unsafeApply(1L)
      val sentinelOrdinal = SnapshotOrdinal.unsafeApply(10L)
      val sentinelKey = GlobalStateKey(HypergraphNamespace, LastStateChannelSnapshotHashes, EmptyNamespace, EmptyNamespace)

      for {
        tmpStorage <- GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](path("tmp"))
        persistedStorage <- GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](path("persisted"))
        fullSnapshotStorage <- GlobalSnapshotLocalFileSystemStorage.make[IO](path("full"))
        snapshotInfoStorage <- GlobalSnapshotInfoLocalFileSystemStorage.make[IO](path("info-json"))
        snapshotInfoKryoStorage <- GlobalSnapshotInfoKryoLocalFileSystemStorage.make[IO](path("info-kryo"))
        checkpointStorage <-
          CombinedSnapshotCheckpointFileSystemStorage.make[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo](path("checkpoint"))
        producer <- InMemoryMerklePatriciaProducer.make[IO]()
        mptStore <- MptStore.make[IO, GlobalStateKey](producer, GlobalStateKey.toHex[IO])
        storage = SnapshotDownloadStorage.make[IO](
          tmpStorage,
          persistedStorage,
          fullSnapshotStorage,
          snapshotInfoStorage,
          snapshotInfoKryoStorage,
          checkpointStorage,
          hashSelect,
          mptStore
        )
        keyPair <- KeyPairGenerator.makeKeyPair[IO]
        genesis = GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)
        signedGenesis <- Signed.forAsyncHasher[IO, GlobalSnapshot](genesis, keyPair)
        info = signedGenesis.value.info.toGlobalSnapshotInfo
        baseIncremental <- GlobalIncrementalSnapshot.fromGlobalSnapshot[IO](signedGenesis.value)
        proof <- GlobalSnapshotInfo.stateProofBuilder[IO].buildProof(info, ordinal)
        signedIncremental <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](
          baseIncremental.copy(ordinal = ordinal, stateProof = proof),
          keyPair
        )
        _ <- storage.writePersisted(signedIncremental)
        _ <- storage.persistSnapshotInfoWithCutoff(ordinal, info)
        _ <- mptStore.syncFull(Map(sentinelKey -> Json.fromString("sentinel")), sentinelOrdinal)
        before <- witness(producer)
        validRead <- storage.readCombinedValidated(ordinal)
        afterValidRead <- witness(producer)
        invalidInfo = info.copy(
          lastStateChannelSnapshotHashes = SortedMap(Address.fromBytes("different-context".getBytes("UTF-8")) -> Hash.empty)
        )
        _ <- snapshotInfoStorage.delete(ordinal)
        _ <- snapshotInfoStorage.write(ordinal, invalidInfo)
        invalidRead <- storage.readCombinedValidated(ordinal)
        afterInvalidRead <- witness(producer)
        snapshotStillPersisted <- persistedStorage.exists(ordinal)
        invalidInfoStillPersisted <- snapshotInfoStorage.exists(ordinal)
      } yield
        expect.same(Some((signedIncremental, info)), validRead) &&
          expect.same(before, afterValidRead) &&
          expect.same(None, invalidRead) &&
          expect.same(before, afterInvalidRead) &&
          expect(snapshotStillPersisted) &&
          expect(invalidInfoStillPersisted)
    }
  }

  test("first incremental genesis validation derives its state proof at the full-genesis ordinal") { implicit res =>
    implicit val (kryoSerializer, jsonSerializer, hasher, securityProvider, metrics) = res
    implicit val hasherSelector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(hasher)

    val hashSelect = new HashSelect {
      def select(ordinal: SnapshotOrdinal): HashLogic = JsonHash
    }

    File.temporaryDirectory() { root =>
      def path(name: String): Path = Path((root / name).pathAsString)

      for {
        tmpStorage <- GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](path("tmp"))
        persistedStorage <- GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](path("persisted"))
        fullSnapshotStorage <- GlobalSnapshotLocalFileSystemStorage.make[IO](path("full"))
        snapshotInfoStorage <- GlobalSnapshotInfoLocalFileSystemStorage.make[IO](path("info-json"))
        snapshotInfoKryoStorage <- GlobalSnapshotInfoKryoLocalFileSystemStorage.make[IO](path("info-kryo"))
        checkpointStorage <-
          CombinedSnapshotCheckpointFileSystemStorage.make[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo](path("checkpoint"))
        producer <- InMemoryMerklePatriciaProducer.make[IO]()
        mptStore <- MptStore.make[IO, GlobalStateKey](producer, GlobalStateKey.toHex[IO])
        storage = SnapshotDownloadStorage.make[IO](
          tmpStorage,
          persistedStorage,
          fullSnapshotStorage,
          snapshotInfoStorage,
          snapshotInfoKryoStorage,
          checkpointStorage,
          hashSelect,
          mptStore
        )
        keyPair <- KeyPairGenerator.makeKeyPair[IO]
        genesis = GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)
        signedGenesis <- Signed.forAsyncHasher[IO, GlobalSnapshot](genesis, keyPair)
        hashedGenesis <- signedGenesis.toHashed[IO]
        firstIncremental <- GlobalSnapshot.mkFirstIncrementalSnapshot[IO](hashedGenesis)
        signedFirstIncremental <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](firstIncremental, keyPair)
        info = genesis.info.toGlobalSnapshotInfo
        _ <- storage.writePersisted(signedFirstIncremental)
        _ <- storage.persistSnapshotInfoWithCutoff(firstIncremental.ordinal, info)
        ordinaryRead <- storage.readCombinedValidated(firstIncremental.ordinal)
        genesisAwareRead <- storage.readCombinedValidatedAtProofOrdinal(firstIncremental.ordinal, SnapshotOrdinal.MinValue)
      } yield
        expect.same(None, ordinaryRead) &&
          expect.same(Some((signedFirstIncremental, info)), genesisAwareRead)
    }
  }

  test("snapshot info cutoff retains an explicitly protected certified activation parent") { implicit res =>
    implicit val (kryoSerializer, jsonSerializer, hasher, securityProvider, metrics) = res
    implicit val hasherSelector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(hasher)

    val hashSelect = new HashSelect {
      def select(ordinal: SnapshotOrdinal): HashLogic = JsonHash
    }

    File.temporaryDirectory() { root =>
      def path(name: String): Path = Path((root / name).pathAsString)

      val protectedOrdinal = SnapshotOrdinal.unsafeApply(123L)
      val ordinaryOldOrdinal = SnapshotOrdinal.unsafeApply(124L)
      val currentOrdinal = SnapshotOrdinal.unsafeApply(1000L)
      val info = GlobalSnapshotInfo.empty

      for {
        tmpStorage <- GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](path("tmp"))
        persistedStorage <- GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](path("persisted"))
        fullSnapshotStorage <- GlobalSnapshotLocalFileSystemStorage.make[IO](path("full"))
        snapshotInfoStorage <- GlobalSnapshotInfoLocalFileSystemStorage.make[IO](path("info-json"))
        snapshotInfoKryoStorage <- GlobalSnapshotInfoKryoLocalFileSystemStorage.make[IO](path("info-kryo"))
        checkpointStorage <-
          CombinedSnapshotCheckpointFileSystemStorage.make[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo](path("checkpoint"))
        producer <- InMemoryMerklePatriciaProducer.make[IO]()
        mptStore <- MptStore.make[IO, GlobalStateKey](producer, GlobalStateKey.toHex[IO])
        storage = SnapshotDownloadStorage.make[IO](
          tmpStorage,
          persistedStorage,
          fullSnapshotStorage,
          snapshotInfoStorage,
          snapshotInfoKryoStorage,
          checkpointStorage,
          hashSelect,
          mptStore,
          protectedSnapshotInfoOrdinals = Set(protectedOrdinal)
        )
        _ <- snapshotInfoStorage.write(protectedOrdinal, info)
        _ <- snapshotInfoStorage.write(ordinaryOldOrdinal, info)
        _ <- storage.persistSnapshotInfoWithCutoff(currentOrdinal, info)
        protectedExists <- snapshotInfoStorage.exists(protectedOrdinal)
        ordinaryOldExists <- snapshotInfoStorage.exists(ordinaryOldOrdinal)
        currentExists <- snapshotInfoStorage.exists(currentOrdinal)
      } yield expect(protectedExists) && expect(!ordinaryOldExists) && expect(currentExists)
    }
  }

  test("first incremental after a nonzero historical checkpoint validates at the checkpoint proof ordinal") { res =>
    val (sharedKryoSerializer, sharedJsonSerializer, currentHasher, sharedSecurityProvider, sharedMetrics) = res
    implicit val kryoSerializer: KryoSerializer[IO] = sharedKryoSerializer
    implicit val jsonSerializer: JsonSerializer[IO] = sharedJsonSerializer
    implicit val securityProvider: SecurityProvider[IO] = sharedSecurityProvider
    implicit val metrics: Metrics[IO] = sharedMetrics

    val checkpointOrdinal = SnapshotOrdinal.unsafeApply(42L)
    val firstIncrementalOrdinal = SnapshotOrdinal.unsafeApply(43L)
    implicit val checkpointStateProofSelector: GlobalStateProofSelector = GlobalStateProofSelector(checkpointOrdinal)
    implicit val historicalHasher: Hasher[IO] = new Hasher[IO] {
      def hash[A: io.circe.Encoder](data: A): IO[Hash] = currentHasher.hash(data)
      def hashBytes(bytes: Array[Byte]): IO[Hash] = currentHasher.hashBytes(bytes)
      def compare[A: io.circe.Encoder](data: A, expectedHash: Hash): IO[Boolean] = currentHasher.compare(data, expectedHash)
      def getLogic(ordinal: SnapshotOrdinal): HashLogic = KryoHash
      def prefixedHash[A: io.circe.Encoder](data: A, prefix: Array[Byte]): IO[Hash] = currentHasher.prefixedHash(data, prefix)
    }
    val historicalHashSelect = new HashSelect {
      def select(ordinal: SnapshotOrdinal): HashLogic = KryoHash
    }
    implicit val hasherSelector: HasherSelector[IO] =
      HasherSelector.forSync[IO](currentHasher, historicalHasher, historicalHashSelect)
    val jsonStorageSelect = new HashSelect {
      def select(ordinal: SnapshotOrdinal): HashLogic = JsonHash
    }

    File.temporaryDirectory() { root =>
      def path(name: String): Path = Path((root / name).pathAsString)

      for {
        tmpStorage <- GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](path("tmp"))
        persistedStorage <- GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](path("persisted"))
        fullSnapshotStorage <- GlobalSnapshotLocalFileSystemStorage.make[IO](path("full"))
        snapshotInfoStorage <- GlobalSnapshotInfoLocalFileSystemStorage.make[IO](path("info-json"))
        snapshotInfoKryoStorage <- GlobalSnapshotInfoKryoLocalFileSystemStorage.make[IO](path("info-kryo"))
        checkpointStorage <-
          CombinedSnapshotCheckpointFileSystemStorage.make[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo](path("checkpoint"))
        producer <- InMemoryMerklePatriciaProducer.make[IO]()
        mptStore <- MptStore.make[IO, GlobalStateKey](producer, GlobalStateKey.toHex[IO])
        storage = SnapshotDownloadStorage.make[IO](
          tmpStorage,
          persistedStorage,
          fullSnapshotStorage,
          snapshotInfoStorage,
          snapshotInfoKryoStorage,
          checkpointStorage,
          jsonStorageSelect,
          mptStore
        )(
          implicitly[cats.effect.Async[IO]],
          implicitly[cats.Parallel[IO]],
          hasherSelector,
          kryoSerializer,
          jsonSerializer,
          metrics,
          checkpointStateProofSelector
        )
        keyPair <- KeyPairGenerator.makeKeyPair[IO]
        genesis = GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)
        signedGenesis <- Signed.forAsyncHasher[IO, GlobalSnapshot](genesis, keyPair)
        info = genesis.info.toGlobalSnapshotInfo
        baseIncremental <- GlobalIncrementalSnapshot.fromGlobalSnapshot[IO](signedGenesis.value)(
          implicitly[cats.Parallel[IO]],
          implicitly[cats.effect.Async[IO]],
          historicalHasher,
          jsonSerializer,
          checkpointStateProofSelector
        )
        checkpointProof <- info.stateProof[IO](checkpointOrdinal)(
          implicitly[cats.Parallel[IO]],
          implicitly[cats.effect.Async[IO]],
          historicalHasher,
          jsonSerializer,
          checkpointStateProofSelector
        )
        signedFirstIncremental <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](
          baseIncremental.copy(ordinal = firstIncrementalOrdinal, stateProof = checkpointProof),
          keyPair
        )
        _ <- storage.writePersisted(signedFirstIncremental)
        _ <- snapshotInfoStorage.write(firstIncrementalOrdinal, info)
        correctRead <- storage.readCombinedValidatedAtProofOrdinal(firstIncrementalOrdinal, checkpointOrdinal)(
          historicalHasher,
          checkpointStateProofSelector
        )
        wrongSameOrdinalRead <- storage.readCombinedValidated(firstIncrementalOrdinal)(
          historicalHasher,
          checkpointStateProofSelector
        )
      } yield
        expect.same(KryoHash, hasherSelector.getForOrdinal(firstIncrementalOrdinal).getLogic(firstIncrementalOrdinal)) &&
          expect.same(Some((signedFirstIncremental, info)), correctRead) &&
          expect.same(None, wrongSameOrdinalRead)
    }
  }

  test("a damaged snapshot-info file is not accepted as a persisted recovery anchor") { implicit res =>
    implicit val (kryoSerializer, jsonSerializer, hasher, securityProvider, metrics) = res
    implicit val hasherSelector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(hasher)

    val hashSelect = new HashSelect {
      def select(ordinal: SnapshotOrdinal): HashLogic = JsonHash
    }

    File.temporaryDirectory() { root =>
      def path(name: String): Path = Path((root / name).pathAsString)

      val ordinal = SnapshotOrdinal.unsafeApply(1L)

      for {
        tmpStorage <- GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](path("tmp"))
        persistedStorage <- GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](path("persisted"))
        fullSnapshotStorage <- GlobalSnapshotLocalFileSystemStorage.make[IO](path("full"))
        snapshotInfoStorage <- GlobalSnapshotInfoLocalFileSystemStorage.make[IO](path("info-json"))
        snapshotInfoKryoStorage <- GlobalSnapshotInfoKryoLocalFileSystemStorage.make[IO](path("info-kryo"))
        checkpointStorage <-
          CombinedSnapshotCheckpointFileSystemStorage.make[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo](path("checkpoint"))
        producer <- InMemoryMerklePatriciaProducer.make[IO]()
        mptStore <- MptStore.make[IO, GlobalStateKey](producer, GlobalStateKey.toHex[IO])
        storage = SnapshotDownloadStorage.make[IO](
          tmpStorage,
          persistedStorage,
          fullSnapshotStorage,
          snapshotInfoStorage,
          snapshotInfoKryoStorage,
          checkpointStorage,
          hashSelect,
          mptStore
        )
        keyPair <- KeyPairGenerator.makeKeyPair[IO]
        genesis = GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)
        signedGenesis <- Signed.forAsyncHasher[IO, GlobalSnapshot](genesis, keyPair)
        incremental <- GlobalIncrementalSnapshot.fromGlobalSnapshot[IO](signedGenesis.value)
        signedIncremental <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](
          incremental.copy(ordinal = ordinal),
          keyPair
        )
        hashed <- signedIncremental.toHashed[IO]
        _ <- storage.writePersisted(signedIncremental)
        _ <- snapshotInfoStorage.write(ordinal, GlobalSnapshotInfo.empty)
        _ <- IO.blocking((root / "info-json" / ordinal.value.value.toString).writeByteArray(Array[Byte](1, 2, 3)))
        usable <- storage.ensurePersistedAnchor(hashed.hash, ordinal)
      } yield expect(!usable)
    }
  }

  test("moving a recovered snapshot replaces an older envelope at the same ordinal") { implicit res =>
    implicit val (kryoSerializer, jsonSerializer, hasher, securityProvider, metrics) = res
    implicit val hasherSelector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(hasher)

    val hashSelect = new HashSelect {
      def select(ordinal: SnapshotOrdinal): HashLogic = JsonHash
    }

    File.temporaryDirectory() { root =>
      def path(name: String): Path = Path((root / name).pathAsString)

      for {
        tmpStorage <- GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](path("tmp"))
        persistedStorage <- GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](path("persisted"))
        fullSnapshotStorage <- GlobalSnapshotLocalFileSystemStorage.make[IO](path("full"))
        snapshotInfoStorage <- GlobalSnapshotInfoLocalFileSystemStorage.make[IO](path("info-json"))
        snapshotInfoKryoStorage <- GlobalSnapshotInfoKryoLocalFileSystemStorage.make[IO](path("info-kryo"))
        checkpointStorage <-
          CombinedSnapshotCheckpointFileSystemStorage.make[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo](path("checkpoint"))
        producer <- InMemoryMerklePatriciaProducer.make[IO]()
        mptStore <- MptStore.make[IO, GlobalStateKey](producer, GlobalStateKey.toHex[IO])
        storage = SnapshotDownloadStorage.make[IO](
          tmpStorage,
          persistedStorage,
          fullSnapshotStorage,
          snapshotInfoStorage,
          snapshotInfoKryoStorage,
          checkpointStorage,
          hashSelect,
          mptStore
        )
        originalKey <- KeyPairGenerator.makeKeyPair[IO]
        replacementKey <- KeyPairGenerator.makeKeyPair[IO]
        genesis = GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)
        signedGenesis <- Signed.forAsyncHasher[IO, GlobalSnapshot](genesis, originalKey)
        incremental <- GlobalIncrementalSnapshot.fromGlobalSnapshot[IO](signedGenesis.value)
        original <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](incremental, originalKey)
        replacement <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](incremental, replacementKey)
        replacementHashed <- replacement.toHashed[IO]
        _ <- persistedStorage.writeUnderOrdinal(original)
        _ <- tmpStorage.writeUnderOrdinal(replacement)
        _ <- storage.moveTmpToPersisted(replacement)
        byOrdinal <- persistedStorage.read(replacement.ordinal)
        byHash <- persistedStorage.read(replacementHashed.hash)
        temporary <- tmpStorage.read(replacement.ordinal)
      } yield
        expect.all(
          original.proofs != replacement.proofs,
          byOrdinal.contains(replacement),
          byHash.contains(replacement),
          temporary.isEmpty
        )
    }
  }

  test("recovery promotion converges after a missing temporary copy and restart retry") { implicit res =>
    implicit val (kryoSerializer, jsonSerializer, hasher, securityProvider, metrics) = res
    implicit val hasherSelector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(hasher)

    val hashSelect = new HashSelect {
      def select(ordinal: SnapshotOrdinal): HashLogic = JsonHash
    }

    File.temporaryDirectory() { root =>
      def path(name: String): Path = Path((root / name).pathAsString)

      for {
        tmpStorage <- GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](path("tmp"))
        persistedStorage <- GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](path("persisted"))
        fullSnapshotStorage <- GlobalSnapshotLocalFileSystemStorage.make[IO](path("full"))
        snapshotInfoStorage <- GlobalSnapshotInfoLocalFileSystemStorage.make[IO](path("info-json"))
        snapshotInfoKryoStorage <- GlobalSnapshotInfoKryoLocalFileSystemStorage.make[IO](path("info-kryo"))
        checkpointStorage <-
          CombinedSnapshotCheckpointFileSystemStorage.make[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo](path("checkpoint"))
        producer <- InMemoryMerklePatriciaProducer.make[IO]()
        mptStore <- MptStore.make[IO, GlobalStateKey](producer, GlobalStateKey.toHex[IO])
        storage = SnapshotDownloadStorage.make[IO](
          tmpStorage,
          persistedStorage,
          fullSnapshotStorage,
          snapshotInfoStorage,
          snapshotInfoKryoStorage,
          checkpointStorage,
          hashSelect,
          mptStore
        )
        originalKey <- KeyPairGenerator.makeKeyPair[IO]
        replacementKey <- KeyPairGenerator.makeKeyPair[IO]
        genesis = GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)
        signedGenesis <- Signed.forAsyncHasher[IO, GlobalSnapshot](genesis, originalKey)
        incremental <- GlobalIncrementalSnapshot.fromGlobalSnapshot[IO](signedGenesis.value)
        original <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](incremental, originalKey)
        replacement <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](
          incremental.copy(epochProgress = EpochProgress(NonNegLong.unsafeFrom(1L))),
          replacementKey
        )
        originalHashed <- original.toHashed[IO]
        replacementHashed <- replacement.toHashed[IO]
        _ <- storage.writePersisted(original)
        _ <- tmpStorage.writeUnderOrdinal(replacement)
        _ <- tmpStorage.delete(replacement.ordinal)
        missingBeforePromotion <- tmpStorage.read(replacement.ordinal)
        _ <- storage.moveTmpToPersisted(replacement)
        warmByOrdinal <- persistedStorage.read(replacement.ordinal)
        warmByHash <- persistedStorage.read(replacementHashed.hash)
        warmOriginal <- persistedStorage.read(originalHashed.hash)
        restartedTmpStorage <- GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](path("tmp"))
        restartedPersistedStorage <- GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](path("persisted"))
        restartedStorage = SnapshotDownloadStorage.make[IO](
          restartedTmpStorage,
          restartedPersistedStorage,
          fullSnapshotStorage,
          snapshotInfoStorage,
          snapshotInfoKryoStorage,
          checkpointStorage,
          hashSelect,
          mptStore
        )
        _ <- restartedStorage.moveTmpToPersisted(replacement)
        coldByOrdinal <- restartedPersistedStorage.read(replacement.ordinal)
        coldByHash <- restartedPersistedStorage.read(replacementHashed.hash)
        coldOriginal <- restartedPersistedStorage.read(originalHashed.hash)
        temporaryAfterRetry <- restartedTmpStorage.read(replacement.ordinal)
      } yield
        expect.all(
          originalHashed.hash != replacementHashed.hash,
          missingBeforePromotion.isEmpty,
          warmByOrdinal.contains(replacement),
          warmByHash.contains(replacement),
          warmOriginal.isEmpty,
          coldByOrdinal.contains(replacement),
          coldByHash.contains(replacement),
          coldOriginal.isEmpty,
          temporaryAfterRetry.isEmpty
        )
    }
  }
}
