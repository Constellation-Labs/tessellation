package io.constellationnetwork.node.shared.infrastructure.snapshot.storage

import cats.effect.std.Supervisor
import cats.effect.{IO, Resource}

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.nodeSharedKryoRegistrar
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, KeyPairGenerator, SecurityProvider}

import better.files._
import eu.timepit.refined.types.numeric.NonNegLong
import fs2.io.file.Path
import org.http4s.headers.ETag
import weaver.MutableIOSuite

/** Durable-ETag sidecar tests for [[CombinedSnapshotCheckpointFileSystemStorage]]. Each test creates a fresh tmpdir, writes a checkpoint
  * through the storage, then RECREATES the storage instance against the same tmpdir to simulate a process restart. The post-restart
  * `getCachedHash` must hydrate from the on-disk sidecar (item 1 of the Phase 3 action plan) and the bandwidth limiter's
  * `getCheckpointSize` must report the correct byte count (item 2).
  */
object CombinedSnapshotCheckpointSidecarSuite extends MutableIOSuite {

  implicit val globalStateProofSelector: GlobalStateProofSelector = GlobalStateProofSelector(SnapshotOrdinal(NonNegLong(Long.MaxValue)))

  type Res = (Supervisor[IO], KryoSerializer[IO], JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  def sharedResource: Resource[IO, Res] = for {
    supervisor <- Supervisor[IO]
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](nodeSharedKryoRegistrar)
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    h = Hasher.forJson[IO]
  } yield (supervisor, ks, j, h, sp)

  private def mkCheckpointStorage(
    tmpDir: File
  ): IO[CombinedSnapshotCheckpointFileSystemStorage[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo]] =
    CombinedSnapshotCheckpointFileSystemStorage.make[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo](Path(tmpDir.pathAsString))

  private def mkSignedIncremental(
    implicit H: Hasher[IO],
    S: SecurityProvider[IO],
    j: JsonSerializer[IO]
  ): IO[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)] =
    KeyPairGenerator.makeKeyPair[IO].flatMap { keyPair =>
      Signed.forAsyncHasher[IO, GlobalSnapshot](GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue), keyPair).flatMap { genesis =>
        GlobalIncrementalSnapshot.fromGlobalSnapshot[IO](genesis).flatMap { snapshot =>
          Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](snapshot, keyPair).map((_, genesis.value.info.toGlobalSnapshotInfo))
        }
      }
    }

  test("getCachedHash hydrates from sidecar after storage instance restart") { res =>
    implicit val (_, _, j, h, sp) = res

    File.temporaryDirectory() { tmpDir =>
      for {
        pair <- mkSignedIncremental
        (snapshot, info) = pair
        hash = Hash("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        // First instance: write the checkpoint (this populates the in-memory hash cache
        // AND the on-disk sidecar via tryWrite).
        storage1 <- mkCheckpointStorage(tmpDir)
        _ <- storage1.tryWrite(snapshot.ordinal, snapshot, info, hash)
        before <- storage1.getCachedHash(snapshot.ordinal)
        // Second instance against the same directory: simulates a process restart. The
        // in-memory hashCache starts empty, so getCachedHash must read from the sidecar.
        storage2 <- mkCheckpointStorage(tmpDir)
        after <- storage2.getCachedHash(snapshot.ordinal)
      } yield expect.all(before.contains(hash), after.contains(hash))
    }
  }

  test("getCachedHash returns None when checkpoint exists but sidecar is absent") { res =>
    implicit val (_, _, j, h, sp) = res

    File.temporaryDirectory() { tmpDir =>
      for {
        pair <- mkSignedIncremental
        (snapshot, info) = pair
        hash = Hash("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        storage1 <- mkCheckpointStorage(tmpDir)
        _ <- storage1.tryWrite(snapshot.ordinal, snapshot, info, hash)
        // Delete only the sidecar to simulate the legacy on-disk state (checkpoint
        // written by a node BEFORE the sidecar code shipped). The miss must NOT pretend
        // to have a hash.
        sidecarFile = tmpDir / s"${snapshot.ordinal.value.value}.meta"
        _ <- IO(sidecarFile.delete())
        storage2 <- mkCheckpointStorage(tmpDir)
        result <- storage2.getCachedHash(snapshot.ordinal)
      } yield expect(result.isEmpty)
    }
  }

  test("getCachedHash returns None for orphan sidecar (checkpoint payload missing)") { res =>
    implicit val (_, _, j, h, sp) = res

    File.temporaryDirectory() { tmpDir =>
      for {
        pair <- mkSignedIncremental
        (snapshot, info) = pair
        hash = Hash("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")
        storage1 <- mkCheckpointStorage(tmpDir)
        _ <- storage1.tryWrite(snapshot.ordinal, snapshot, info, hash)
        // Delete only the checkpoint body, leaving the sidecar as an orphan. We must
        // refuse to claim cached state we cannot serve.
        checkpointFile = tmpDir / snapshot.ordinal.value.value.toString
        _ <- IO(checkpointFile.delete())
        storage2 <- mkCheckpointStorage(tmpDir)
        result <- storage2.getCachedHash(snapshot.ordinal)
      } yield expect(result.isEmpty)
    }
  }

  test("second tryWrite at same ordinal overwrites sidecar with new hash") { res =>
    implicit val (_, _, j, h, sp) = res

    File.temporaryDirectory() { tmpDir =>
      for {
        pair <- mkSignedIncremental
        (snapshot, info) = pair
        oldHash = Hash("1111111111111111111111111111111111111111111111111111111111111111")
        newHash = Hash("2222222222222222222222222222222222222222222222222222222222222222")
        storage1 <- mkCheckpointStorage(tmpDir)
        _ <- storage1.tryWrite(snapshot.ordinal, snapshot, info, oldHash)
        _ <- storage1.tryWrite(snapshot.ordinal, snapshot, info, newHash)
        // Force a "cold restart" so the in-memory cache cannot mask the sidecar.
        storage2 <- mkCheckpointStorage(tmpDir)
        result <- storage2.getCachedHash(snapshot.ordinal)
      } yield expect(result.contains(newHash))
    }
  }

  test("getCheckpointSize reports the on-disk body size, None when absent") { res =>
    implicit val (_, _, j, h, sp) = res

    File.temporaryDirectory() { tmpDir =>
      for {
        pair <- mkSignedIncremental
        (snapshot, info) = pair
        hash = Hash("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd")
        // Missing checkpoint case BEFORE the write.
        storage <- mkCheckpointStorage(tmpDir)
        missing <- storage.getCheckpointSize(snapshot.ordinal)
        _ <- storage.tryWrite(snapshot.ordinal, snapshot, info, hash)
        present <- storage.getCheckpointSize(snapshot.ordinal)
        onDisk = tmpDir / snapshot.ordinal.value.value.toString
        diskSize = onDisk.size
      } yield
        expect.all(
          missing.isEmpty,
          present.contains(diskSize)
        )
    }
  }

  test("parseSidecar accepts the canonical render and rejects garbage") { res =>
    implicit val (_, _, j, h, sp) = res

    File.temporaryDirectory() { tmpDir =>
      for {
        storage <- mkCheckpointStorage(tmpDir)
        // Canonical render is `ordinal=N,hash=H,size=S\n`; we exercise both the
        // happy-path parse and the "malformed payload returns None" branch that
        // protects the route from emitting an ETag against unreadable state.
        ordHash = Hash("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
        good = "ordinal=42,hash=" + ordHash.value + ",size=1234\n"
        bad1 = "ordinal=notanumber,hash=" + ordHash.value + ",size=1234"
        bad2 = "ordinal=42,size=1234" // missing hash
        bad3 = ""
        parsedGood = storage.parseSidecar(good)
        parsedBad1 = storage.parseSidecar(bad1)
        parsedBad2 = storage.parseSidecar(bad2)
        parsedBad3 = storage.parseSidecar(bad3)
      } yield
        expect.all(
          parsedGood.exists { case (o, hh, s) => o.value.value == 42L && hh == ordHash && s == 1234L },
          parsedBad1.isEmpty,
          parsedBad2.isEmpty,
          parsedBad3.isEmpty
        )
    }
  }

  // ----------------------------------------------------------------------------
  // codex finding 3: getAsHttpResponse must be sidecar-aware. Routes that go
  // through the request handler call getCachedHash first, but any caller that
  // invokes getAsHttpResponse directly (without that prefetch) must still
  // recover the ETag from the on-disk sidecar after a cold restart. The legacy
  // implementation queried hashCache.getIfPresent directly and silently dropped
  // the ETag in that scenario, costing peers the 304 short-circuit.
  // ----------------------------------------------------------------------------
  test("getAsHttpResponse emits ETag from sidecar after cold restart (codex finding 3)") { res =>
    implicit val (_, _, j, h, sp) = res

    File.temporaryDirectory() { tmpDir =>
      for {
        pair <- mkSignedIncremental
        (snapshot, info) = pair
        hash = Hash("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")
        storage1 <- mkCheckpointStorage(tmpDir)
        _ <- storage1.tryWrite(snapshot.ordinal, snapshot, info, hash)
        // Recreate the storage against the same tmpdir to simulate a process restart
        // (in-memory hashCache wiped). getAsHttpResponse must still find the hash via
        // the sidecar fallback path inside getCachedHash.
        storage2 <- mkCheckpointStorage(tmpDir)
        respOpt <- storage2.getAsHttpResponse(snapshot.ordinal)
        // Drain the body so the streaming resource releases its semaphore permit; the
        // assertions below only need the headers, but leaving the body undrained would
        // hold the permit and skew any subsequent test ordering.
        _ <- respOpt.fold(IO.unit)(_.body.compile.drain)
        expectedEtag = storage2.etagFor(snapshot.ordinal, hash)
        actualEtag = respOpt.flatMap(_.headers.get[ETag].map(_.tag))
      } yield
        expect.all(
          respOpt.isDefined,
          actualEtag.contains(expectedEtag)
        )
    }
  }
}
