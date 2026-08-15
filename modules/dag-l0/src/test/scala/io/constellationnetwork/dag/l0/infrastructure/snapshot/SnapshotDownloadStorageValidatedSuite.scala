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
}
