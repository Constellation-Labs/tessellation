package io.constellationnetwork.node.shared.infrastructure.snapshot.storage

import cats.effect.std.Supervisor
import cats.effect.{IO, Resource}

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.ext.kryo._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.nodeSharedKryoRegistrar
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.shared.sharedKryoRegistrar
import io.constellationnetwork.storage.PathGenerator
import io.constellationnetwork.storage.PathGenerator._

import better.files._
import eu.timepit.refined.auto._
import fs2.io.file.Path
import weaver.MutableIOSuite

// Regressions for retained torn bodies on the canonical replay path.
object TornHashBodyRecoverySuite extends MutableIOSuite {

  val hashPathGenerator = PathGenerator.forHash(Depth(2), PrefixSize(3))
  val ordinalPathGenerator = PathGenerator.forOrdinal(ChunkSize(20000))

  type Res = (Supervisor[IO], KryoSerializer[IO], JsonSerializer[IO], Hasher[IO], SecurityProvider[IO], GlobalStateProofSelector)

  override def sharedResource: Resource[IO, Res] =
    for {
      s <- Supervisor[IO]
      implicit0(k: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar.union(nodeSharedKryoRegistrar))
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
      h = Hasher.forJson[IO]
      sp <- SecurityProvider.forAsync[IO]
      gsps = GlobalStateProofSelector(SnapshotOrdinal(Long.MaxValue))
    } yield (s, k, j, h, sp, gsps)

  private def tornSetup(tmpDir: File)(
    implicit k: KryoSerializer[IO],
    j: JsonSerializer[IO],
    h: Hasher[IO],
    sp: SecurityProvider[IO],
    gsps: GlobalStateProofSelector,
    hs: HasherSelector[IO]
  ) =
    for {
      storage <- GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](Path(tmpDir.pathAsString))
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      genesis <- Signed.forAsyncHasher[IO, GlobalSnapshot](GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue), keyPair)
      base <- GlobalIncrementalSnapshot.fromGlobalSnapshot[IO](genesis)
      anchor <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](base.copy(ordinal = SnapshotOrdinal.unsafeApply(10L)), keyPair)
      next <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](base.copy(ordinal = SnapshotOrdinal.unsafeApply(11L)), keyPair)
      nextHash <- next.value.hash
      _ <- storage.write(anchor)
      // Simulate a crash mid-way through the historical non-atomic hash body write of ordinal 11:
      // the hash file exists with truncated bytes and no ordinal link was ever created.
      hashFile = tmpDir / "hash" / hashPathGenerator.get(nextHash.value)
      _ <- storage.write(next)
      _ <- storage.delete(next.ordinal)
      full <- IO.blocking(hashFile.byteArray)
      _ <- IO.blocking(hashFile.writeByteArray(full.take(full.length / 2)))
      // Recovery at anchor 10 with the change under review: no hash-tree pass, torn body retained.
      _ <- storage.cleanupAboveOrdinal(SnapshotOrdinal.unsafeApply(10L), (hash, ordinal) => storage.delete(hash) >> storage.delete(ordinal))
      tornRetained <- IO.blocking(hashFile.exists)
    } yield (storage, next, nextHash, hashFile, full.length, tornRetained)

  test("A: retained torn hash is unreadable without aborting anchor search") { res =>
    implicit val (_, kryo, j, h, sp, gsps) = res
    implicit val selector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(h)

    File.temporaryDirectory() { tmpDir =>
      for {
        setup <- tornSetup(tmpDir)
        (storage, next, nextHash, _, _, tornRetained) = setup
        // GL0 walk-back calls ensurePersistedAnchor -> ensureOrdinalLink for every canonical (hash, ordinal) step.
        linkStatus <- storage.ensureOrdinalLink(nextHash, next.ordinal).attempt
        readByHash <- storage.read(nextHash).attempt
        ordinalExists <- storage.exists(next.ordinal)
      } yield
        expect(tornRetained, "torn body must have survived cleanup")
          .and(expect(linkStatus.contains(SnapshotLocalFileSystemStorage.OrdinalLinkStatus.HashUnreadable), s"link status: $linkStatus"))
          .and(expect(readByHash.contains(None), s"read(hash) must return None; got: $readByHash"))
          .and(expect(!ordinalExists, "torn bytes must not be linked during anchor search"))
    }
  }

  test("B: ordinary write atomically repairs a retained torn body before linking its ordinal") { res =>
    implicit val (_, kryo, j, h, sp, gsps) = res
    implicit val selector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(h)

    File.temporaryDirectory() { tmpDir =>
      for {
        setup <- tornSetup(tmpDir)
        (storage, next, _, hashFile, fullLength, tornRetained) = setup
        // Keep the old inode alive: comparing only the final hash and ordinal paths would
        // incorrectly forbid the normal hard link to the newly repaired hash inode.
        tornWitness = tmpDir / "torn-inode-witness"
        _ <- IO.blocking(hashFile.linkTo(tornWitness))
        // Consensus `enqueue` and GL0 catch-up `writePersisted` both persist through this path.
        writeResult <- storage.write(next).attempt
        ordinalFile = tmpDir / "ordinal" / ordinalPathGenerator.get("11")
        ordinalExists <- IO.blocking(ordinalFile.exists)
        linkedToRepairedBody <- IO.blocking(ordinalExists && ordinalFile.isSameFileAs(hashFile))
        linkedToTornBody <- IO.blocking(ordinalExists && ordinalFile.isSameFileAs(tornWitness))
        witnessLength <- IO.blocking(tornWitness.size)
        hashLen <- IO.blocking(hashFile.size)
        byOrdinal <- storage.read(next.ordinal).attempt
      } yield
        expect(tornRetained, "torn body must have survived cleanup")
          .and(expect(writeResult.isRight, s"write result: $writeResult"))
          .and(expect(ordinalExists, "ordinal index was created by the write"))
          .and(expect(linkedToRepairedBody, "ordinal must link to the repaired hash inode"))
          .and(expect(!linkedToTornBody, "ordinal must not link to the original torn inode"))
          .and(expect(witnessLength == (fullLength / 2).toLong, "atomic replacement must leave the old inode untouched"))
          .and(expect(hashLen == fullLength.toLong, s"hash body must be rewritten to full length; got $hashLen of $fullLength"))
          .and(expect(byOrdinal.exists(_.contains(next)), s"ordinal 11 must be readable after write; got $byOrdinal"))
    }
  }
  test("an existing torn ordinal reads as absent so recovery can replace it") { res =>
    implicit val (_, kryo, j, h, sp, gsps) = res
    implicit val selector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(h)

    File.temporaryDirectory() { tmpDir =>
      for {
        setup <- tornSetup(tmpDir)
        (storage, next, nextHash, hashFile, _, _) = setup
        ordinalFile = tmpDir / "ordinal" / ordinalPathGenerator.get(next.ordinal.value.value.toString)
        // Model an index poisoned by the former blind writer, before this fix was deployed.
        _ <- IO.blocking(hashFile.linkTo(ordinalFile))
        before <- storage.read(next.ordinal)
        status <- storage.ensureOrdinalLink(nextHash, next.ordinal)
        _ <- storage.replaceForRecovery(next)
        after <- storage.read(next.ordinal)
        byHash <- storage.read(nextHash)
      } yield
        expect.all(
          before.isEmpty,
          status == SnapshotLocalFileSystemStorage.OrdinalLinkStatus.HashUnreadable,
          after.contains(next),
          byHash.contains(next)
        )
    }
  }

}
