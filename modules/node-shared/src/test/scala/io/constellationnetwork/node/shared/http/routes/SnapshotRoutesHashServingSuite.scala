package io.constellationnetwork.node.shared.http.routes

import java.io.IOException

import cats.effect.std.Supervisor
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.ext.kryo._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.config.types.SnapshotTimeoutsConfig
import io.constellationnetwork.node.shared.infrastructure.metrics.{Metrics, NoOpMetrics}
import io.constellationnetwork.node.shared.infrastructure.node.NodeStorage
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage._
import io.constellationnetwork.node.shared.nodeSharedKryoRegistrar
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.shared.sharedKryoRegistrar

import better.files.File
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import fs2.io.file.Path
import org.http4s._
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import weaver.MutableIOSuite

object SnapshotRoutesHashServingSuite extends MutableIOSuite {
  implicit val stateProofSelector: GlobalStateProofSelector = GlobalStateProofSelector(SnapshotOrdinal(NonNegLong(Long.MaxValue)))
  implicit val metrics: Metrics[IO] = NoOpMetrics.make

  type Res = (Supervisor[IO], KryoSerializer[IO], JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  def sharedResource: Resource[IO, Res] = for {
    supervisor <- Supervisor[IO]
    kryo <- KryoSerializer.forAsync[IO](sharedKryoRegistrar.union(nodeSharedKryoRegistrar))
    implicit0(json: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    security <- SecurityProvider.forAsync[IO]
  } yield (supervisor, kryo, json, Hasher.forJson[IO], security)

  private def snapshot(implicit j: JsonSerializer[IO], h: Hasher[IO], sp: SecurityProvider[IO]): IO[Signed[GlobalIncrementalSnapshot]] =
    for {
      key <- KeyPairGenerator.makeKeyPair[IO]
      base <- GlobalIncrementalSnapshot.fromGlobalSnapshot[IO](GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue))
      signed <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](base.copy(ordinal = SnapshotOrdinal.unsafeApply(11L)), key)
    } yield signed

  private def routes(root: File, files: SnapshotLocalFileSystemStorage[IO, GlobalIncrementalSnapshot], selector: HasherSelector[IO])(
    implicit supervisor: Supervisor[IO],
    j: JsonSerializer[IO],
    k: KryoSerializer[IO]
  ) =
    for {
      infos <- GlobalSnapshotInfoLocalFileSystemStorage.make[IO](Path((root / "info").pathAsString))
      checkpoints <- CombinedSnapshotCheckpointFileSystemStorage.make[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
        Path((root / "checkpoints").pathAsString)
      )
      storage <- SnapshotStorage.make[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
        files,
        infos,
        NonNegLong(5L),
        SnapshotOrdinal.MinValue,
        selector,
        checkpoints
      )
      node <- NodeStorage.make[IO]
      _ <- node.setNodeState(NodeState.Ready)
      api <- SnapshotRoutes.make[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
        storage,
        None,
        None,
        "/global-snapshots",
        node,
        selector,
        SnapshotTimeoutsConfig(10.seconds, 10.seconds),
        checkpoints
      )
    } yield (api, storage)

  private def request(path: String): Request[IO] =
    Request[IO](uri = Uri.unsafeFromString(path)).putHeaders(org.http4s.headers.Accept(MediaType.application.json))

  private def fetchBoth(api: SnapshotRoutes[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo], hash: Hash) =
    List(api.publicRoutes, api.p2pRoutes).traverse { route =>
      route.orNotFound.run(request(s"/global-snapshots/${hash.value}")).flatMap { response =>
        if (response.status == Status.Ok)
          response.as[Signed[GlobalIncrementalSnapshot]].map(s => (response.status, s.some))
        else response.body.compile.drain.as((response.status, none[Signed[GlobalIncrementalSnapshot]]))
      }
    }

  List(false, true).foreach { copied =>
    test(s"public and peer hash reads serve matching ordinal indexes with copied=$copied") { res =>
      implicit val (supervisor, kryo, json, hasher, security) = res
      val selector = HasherSelector.forSyncAlwaysCurrent(hasher)
      File.temporaryDirectory() { root =>
        for {
          files <- GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](Path((root / "snapshots").pathAsString))
          s <- snapshot
          hash <- s.value.hash
          _ <- files.write(s)
          hashFile <- files.getPath(hash)
          ordinalFile <- files.getPath("ordinal/" + files.ordinalPathGenerator.get(s.ordinal.value.value.toString))
          _ <- IO.blocking {
            if (copied) {
              val bytes = ordinalFile.byteArray
              ordinalFile.delete()
              ordinalFile.writeByteArray(bytes)
            }
          }
          linked <- IO.blocking(ordinalFile.isSameFileAs(hashFile))
          built <- routes(root, files, selector)
          (api, _) = built
          responses <- fetchBoth(api, hash)
        } yield expect.all(linked == !copied, clue(responses).forall(_ == ((Status.Ok, s.some))))
      }
    }
  }

  List("missing", "unreadable", "different-hash", "wrong-ordinal").foreach { index =>
    test(s"public and peer hash reads reject $index ordinal without repairing or deleting retained bytes") { res =>
      implicit val (supervisor, kryo, json, hasher, security) = res
      val selector = HasherSelector.forSyncAlwaysCurrent(hasher)
      File.temporaryDirectory() { root =>
        for {
          files <- GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](Path((root / "snapshots").pathAsString))
          s <- snapshot
          hash <- s.value.hash
          _ <- files.write(s) >> files.delete(s.ordinal)
          hashFile <- files.getPath(hash)
          ordinalFile <- files.getPath("ordinal/" + files.ordinalPathGenerator.get(s.ordinal.value.value.toString))
          key <- KeyPairGenerator.makeKeyPair[IO]
          other <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](
            s.value.copy(
              ordinal = if (index == "wrong-ordinal") SnapshotOrdinal.unsafeApply(12L) else s.ordinal,
              epochProgress = EpochProgress(NonNegLong(1L))
            ),
            key
          )
          otherBytes <- JsonSerializer[IO].serialize(other)
          _ <- IO.blocking {
            if (index == "unreadable") ordinalFile.writeByteArray(hashFile.byteArray.take(7))
            else if (index != "missing") ordinalFile.writeByteArray(otherBytes)
          }
          beforeHash <- IO.blocking(hashFile.byteArray.toVector)
          beforeOrdinal <- IO.blocking(Option.when(ordinalFile.exists)(ordinalFile.byteArray.toVector))
          built <- routes(root, files, selector)
          (api, _) = built
          responses <- fetchBoth(api, hash)
          afterHash <- IO.blocking(hashFile.byteArray.toVector)
          afterOrdinal <- IO.blocking(Option.when(ordinalFile.exists)(ordinalFile.byteArray.toVector))
          retained <- files.read(hash)
        } yield
          expect.all(
            clue(responses).forall(_._1 == Status.NotFound),
            retained.contains(s),
            beforeHash == afterHash,
            beforeOrdinal == afterOrdinal
          )
      }
    }
  }

  test("a shared inode containing a torn hash body is not served or repaired") { res =>
    implicit val (supervisor, kryo, json, hasher, security) = res
    File.temporaryDirectory() { root =>
      for {
        files <- GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](Path((root / "snapshots").pathAsString))
        s <- snapshot
        hash <- s.value.hash
        _ <- files.write(s)
        file <- files.getPath(hash)
        before <- IO.blocking {
          val torn = file.byteArray.take(7)
          file.writeByteArray(torn)
          torn.toVector
        }
        built <- routes(root, files, HasherSelector.forSyncAlwaysCurrent(hasher))
        (api, _) = built
        responses <- fetchBoth(api, hash)
        after <- IO.blocking(file.byteArray.toVector)
        ordinalStillExists <- files.exists(s.ordinal)
      } yield expect.all(clue(responses).forall(_._1 == Status.NotFound), before == after, ordinalStillExists)
    }
  }

  test("hash reads reject a readable body stored under the wrong requested hash") { res =>
    implicit val (supervisor, kryo, json, hasher, security) = res
    File.temporaryDirectory() { root =>
      for {
        files <- GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](Path((root / "snapshots").pathAsString))
        s <- snapshot
        _ <- files.write(s)
        realHash <- s.value.hash
        wrongHash = Hash("a" * 64)
        original <- files.getPath(realHash)
        wrong <- files.getPath(wrongHash)
        _ <- IO.blocking { wrong.parent.createDirectories(); wrong.writeByteArray(original.byteArray) }
        built <- routes(root, files, HasherSelector.forSyncAlwaysCurrent(hasher))
        (api, _) = built
        responses <- fetchBoth(api, wrongHash)
      } yield expect(clue(responses).forall(_._1 == Status.NotFound))
    }
  }

  test("hash reads select the hasher for the requested snapshot ordinal") { res =>
    implicit val (supervisor, kryo, json, hasher, security) = res
    val selector = new HasherSelector[IO] {
      def getForOrdinal(ordinal: SnapshotOrdinal): Hasher[IO] = {
        require(ordinal == SnapshotOrdinal.unsafeApply(11L))
        hasher
      }
      def getCurrent: Hasher[IO] = throw new IllegalStateException("historical serving must select by ordinal")
    }
    File.temporaryDirectory() { root =>
      for {
        files <- GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](Path((root / "snapshots").pathAsString))
        s <- snapshot
        hash <- s.value.hash
        _ <- files.write(s)
        built <- routes(root, files, selector)
        (api, _) = built
        responses <- fetchBoth(api, hash)
      } yield expect(clue(responses).forall(_ == ((Status.Ok, s.some))))
    }
  }

  test("hash reads preserve accepted in-memory ordinal mappings") { res =>
    implicit val (supervisor, kryo, json, hasher, security) = res
    File.temporaryDirectory() { root =>
      for {
        files <- GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](Path((root / "snapshots").pathAsString))
        s <- snapshot
        hash <- s.value.hash
        built <- routes(root, files, HasherSelector.forSyncAlwaysCurrent(hasher))
        (api, storage) = built
        _ <- storage.prepend(s, GlobalSnapshotInfo.empty)
        // The accepted ordinal mapping and body are authoritative in memory even before offload.
        _ <- files.delete(s.ordinal) >> files.delete(hash)
        responses <- fetchBoth(api, hash)
      } yield expect(clue(responses).forall(_ == ((Status.Ok, s.some))))
    }
  }

  test("ordinal read I/O errors propagate from hash serving without changing the stored indexes") { res =>
    implicit val (supervisor, kryo, json, hasher, security) = res
    File.temporaryDirectory() { root =>
      val path = Path((root / "snapshots").pathAsString)
      for {
        files <- GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](path)
        s <- snapshot
        hash <- s.value.hash
        _ <- files.write(s)
        hashFile <- files.getPath(hash)
        before <- IO.blocking(hashFile.byteArray.toVector)
        failing = new SnapshotLocalFileSystemStorage[IO, GlobalIncrementalSnapshot](path) {
          def deserializeFallback(bytes: Array[Byte]): Either[Throwable, Signed[GlobalIncrementalSnapshot]] =
            Left(new IllegalArgumentException("unused fallback"))
          override def readBytes(name: String): IO[Option[Array[Byte]]] =
            if (name.startsWith("ordinal/")) IO.raiseError(new IOException("simulated ordinal I/O failure"))
            else super.readBytes(name)
        }
        built <- routes(root, failing, HasherSelector.forSyncAlwaysCurrent(hasher))
        (api, _) = built
        results <- List(api.publicRoutes, api.p2pRoutes).traverse(
          _.orNotFound.run(request(s"/global-snapshots/${hash.value}")).attempt
        )
        after <- IO.blocking(hashFile.byteArray.toVector)
        linked <- files.ensureOrdinalLink(hash, s.ordinal)
      } yield
        expect.all(
          clue(results).forall(_.swap.exists(_.isInstanceOf[IOException])),
          before == after,
          linked == SnapshotLocalFileSystemStorage.OrdinalLinkStatus.Linked
        )
    }
  }
  test("Currency public and peer hash reads reject retained orphans and serve indexed snapshots") { res =>
    implicit val (supervisor, kryo, json, hasher, security) = res
    import io.constellationnetwork.currency.schema.currency._
    implicit val currencySelector: CurrencyStateProofSelector = CurrencyStateProofSelector.instance
    val selector = HasherSelector.forSyncAlwaysCurrent(hasher)
    File.temporaryDirectory() { root =>
      for {
        files <- CurrencyIncrementalSnapshotLocalFileSystemStorage.make[IO](Path((root / "snapshots").pathAsString))
        infos <- CurrencySnapshotInfoLocalFileSystemStorage.make[IO](Path((root / "info").pathAsString))
        checkpoints <- CombinedSnapshotCheckpointFileSystemStorage.make[IO, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
          Path((root / "checkpoints").pathAsString)
        )
        storage <- SnapshotStorage.make[IO, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
          files,
          infos,
          NonNegLong(5L),
          SnapshotOrdinal.MinValue,
          selector,
          checkpoints
        )
        node <- NodeStorage.make[IO]
        _ <- node.setNodeState(NodeState.WaitingForReady)
        api <- SnapshotRoutes.make[IO, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
          storage,
          None,
          None,
          "/snapshots",
          node,
          selector,
          SnapshotTimeoutsConfig(10.seconds, 10.seconds),
          checkpoints
        )
        key <- KeyPairGenerator.makeKeyPair[IO]
        genesis <- Signed.forAsyncHasher[IO, CurrencySnapshot](CurrencySnapshot.mkGenesis(Map.empty, None, None), key)
        hashedGenesis <- genesis.toHashed[IO]
        base <- CurrencySnapshot.mkFirstIncrementalSnapshot[IO](hashedGenesis)
        canonical <- Signed.forAsyncHasher[IO, CurrencyIncrementalSnapshot](base, key)
        orphan <- Signed.forAsyncHasher[IO, CurrencyIncrementalSnapshot](base.copy(ordinal = SnapshotOrdinal.unsafeApply(40001L)), key)
        canonicalHash <- canonical.toHashed[IO].map(_.hash)
        orphanHash <- orphan.toHashed[IO].map(_.hash)
        _ <- files.write(canonical) >> files.write(orphan) >> files.delete(orphan.ordinal)
        responses <- List(api.publicRoutes, api.p2pRoutes).traverse { route =>
          List(canonicalHash, orphanHash).traverse { hash =>
            route.orNotFound
              .run(request(s"/snapshots/${hash.value}"))
              .flatMap(response => response.body.compile.drain.as(response.status))
          }
        }
        orphanRetained <- files.exists(orphanHash)
        orphanIndexed <- files.exists(orphan.ordinal)
      } yield expect.all(clue(responses).forall(_ == List(Status.Ok, Status.NotFound)), orphanRetained, !orphanIndexed)
    }
  }

}
