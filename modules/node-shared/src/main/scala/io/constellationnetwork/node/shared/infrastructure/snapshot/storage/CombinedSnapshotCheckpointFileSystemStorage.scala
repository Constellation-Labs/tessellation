package io.constellationnetwork.node.shared.infrastructure.snapshot.storage

import java.io.{FileOutputStream, OutputStreamWriter}

import cats.effect.std.Semaphore
import cats.effect.{Async, Concurrent, Resource}
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.schema._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.storage.LocalFileSystemStorage

import com.github.blemale.scaffeine.{Cache, Scaffeine}
import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import fs2.concurrent.SignallingRef
import fs2.io.file.{Files, Flags, Path}
import fs2.{Stream, text}
import io.circe.syntax._
import io.circe.{Encoder, Printer}
import org.http4s._
import org.http4s.headers._
import org.typelevel.log4cats.slf4j.Slf4jLogger

@derive(eqv, show, encoder, decoder)
case class LastCheckpointInfo(
  ordinal: SnapshotOrdinal,
  epochProgress: EpochProgress,
  hash: Hash
)

object LastCheckpointInfo {
  def empty(): LastCheckpointInfo =
    LastCheckpointInfo(
      SnapshotOrdinal.MinValue,
      EpochProgress.MinValue,
      Hash.empty
    )
}

final class CombinedSnapshotCheckpointFileSystemStorage[
  F[_]: Async: Files,
  S <: Snapshot,
  SI <: SnapshotInfo[_]
](
  path: Path,
  lastSnapshotInfo: SignallingRef[F, LastCheckpointInfo],
  // Bounds concurrent open file streams; permit is held for the entire stream lifetime
  // including slow consumer drain. Lowering this is the primary lever for capping disk
  // contention from concurrent heavy-route serves.
  concurrentStreams: Semaphore[F],
  // Parallel cache populated at write time so per-ordinal requests can emit a strong-validator
  // ETag of `<ordinal>-<snapshotHash>` rather than ordinal-only. The ordinal-only form would be
  // a lying validator: ord-N can map to different bytes on different forks, and a peer holding
  // stale (N, H1) querying with `If-None-Match: "N"` would falsely 304 against the canonical
  // (N, H2). Snapshots loaded from disk after a restart that aren't in this cache fall back to
  // emitting no ETag -- strictly correct (always 200), just no optimization for that ordinal.
  hashCache: Cache[SnapshotOrdinal, Hash]
)(
  implicit encSigned: Encoder[Signed[S]],
  encState: Encoder[SI]
) extends LocalFileSystemStorage[F, Array[Byte]](path) {
  // These aren't necessary on pureconfig, because in theory they should never change, so I'll hardcode
  private val maxCheckpointsStored = 2
  private val checkpointIntervalEpochs = 5

  private val sidecarLogger = Slf4jLogger.getLoggerFromClass[F](this.getClass)

  // Suffix appended to the checkpoint ordinal filename to form the sidecar metadata path.
  // Keeping the suffix non-numeric ensures `listStoredOrdinals` (which parses filenames as
  // Long via `_.toLongOption`) silently filters sidecars out -- no accidental listing as
  // a checkpoint ordinal.
  private val sidecarSuffix = ".meta"

  private def writeJsonTupleStream(
    ordinal: SnapshotOrdinal,
    snapshot: Signed[S],
    state: SI
  ): F[Unit] = {
    val filePath = path / ordinal.value.value.toString

    val openFile: Resource[F, (FileOutputStream, OutputStreamWriter)] =
      Resource.make(
        Concurrent[F].blocking {
          // Ensure parent directory exists before creating file
          val file = new java.io.File(filePath.toString)
          Option(file.getParentFile).foreach(_.mkdirs())
          val fos = new FileOutputStream(file)
          val writer = new OutputStreamWriter(fos, "UTF-8")
          (fos, writer)
        }
      ) {
        case (fos, writer) =>
          Concurrent[F].blocking {
            writer.flush()
            writer.close()
            fos.close()
          }.handleErrorWith(_ => Concurrent[F].unit)
      }

    openFile.use {
      case (_, writer) =>
        val printer = Printer.noSpaces.copy(dropNullValues = true)
        Concurrent[F].blocking {
          writer.append('[')
          printer.unsafePrintToAppendable(snapshot.asJson, writer)
          writer.append(',')
          printer.unsafePrintToAppendable(state.asJson, writer)
          writer.append(']')
          ()
        }
    }
  }

  /** Path of the on-disk sidecar metadata file for `ordinal`. Co-located with the checkpoint so retention/eviction can clean both with a
    * single ordinal-driven sweep.
    */
  private def sidecarPath(ordinal: SnapshotOrdinal): Path =
    path / s"${ordinal.value.value}$sidecarSuffix"

  /** Render the sidecar payload. Compact `key=value,key=value` line keeps the on-disk footprint to a single short ASCII line, avoids
    * pulling in JSON decoders on the cold-read path, and is trivial to inspect with `cat`/`grep`. A trailing newline lets `od`/`cat` users
    * see the file contents cleanly without an unterminated last line.
    */
  private def renderSidecar(ordinal: SnapshotOrdinal, snapshotHash: Hash, size: Long): String =
    s"ordinal=${ordinal.value.value},hash=${snapshotHash.value},size=$size\n"

  /** Parse the sidecar payload back into its components. Returns `None` on any structural defect -- callers fall back to the cold-read
    * behavior (no ETag emission) rather than fabricating a value.
    */
  private[storage] def parseSidecar(content: String): Option[(SnapshotOrdinal, Hash, Long)] = {
    val trimmed = content.trim
    if (trimmed.isEmpty) None
    else {
      val pairs = trimmed
        .split(',')
        .iterator
        .flatMap { kv =>
          val idx = kv.indexOf('=')
          if (idx <= 0) Iterator.empty else Iterator.single(kv.substring(0, idx).trim -> kv.substring(idx + 1).trim)
        }
        .toMap
      for {
        ordRaw <- pairs.get("ordinal")
        hashStr <- pairs.get("hash")
        sizeRaw <- pairs.get("size")
        ordLong <- ordRaw.toLongOption
        ordinal <- SnapshotOrdinal(ordLong)
        size <- sizeRaw.toLongOption
        if hashStr.nonEmpty
      } yield (ordinal, Hash(hashStr), size)
    }
  }

  /** Write the sidecar next to the checkpoint. Errors are logged and swallowed: a failed sidecar must not abort the checkpoint commit (the
    * in-memory hash cache still lets the current process emit ETags; the sidecar miss only costs ETag emission on the NEXT cold restart,
    * which is the same fallback the route layer already tolerates).
    */
  private def writeSidecar(ordinal: SnapshotOrdinal, snapshotHash: Hash, size: Long): F[Unit] = {
    val sidecar = sidecarPath(ordinal)
    val payload = renderSidecar(ordinal, snapshotHash, size).getBytes("UTF-8")
    val ensureParent = sidecar.parent.fold(Async[F].unit)(p => Files[F].createDirectories(p))
    val write = Stream.emits(payload).through(Files[F].writeAll(sidecar)).compile.drain
    (ensureParent >> write).handleErrorWith { t =>
      sidecarLogger.warn(t)(s"Failed to write sidecar for ordinal ${ordinal.value.value}; ETag will fall back to no-emit on cold restart")
    }
  }

  /** Read the sidecar for `ordinal`. Returns `None` when:
    *   - the sidecar file is absent (legacy checkpoints written before this code shipped);
    *   - the parse fails (e.g. partial write / disk corruption);
    *   - the parsed ordinal disagrees with the queried ordinal (refuse to silently lie about state);
    *   - the corresponding checkpoint file is absent (orphan sidecar; refuse to claim cached state we cannot serve).
    */
  private def readSidecar(ordinal: SnapshotOrdinal): F[Option[Hash]] = {
    val sidecar = sidecarPath(ordinal)
    val checkpoint = path / ordinal.value.value.toString
    Files[F].exists(sidecar).flatMap {
      case false => none[Hash].pure[F]
      case true =>
        Files[F].exists(checkpoint).flatMap {
          case false =>
            // Orphan sidecar (checkpoint deleted but sidecar lingered). Don't lie about state.
            none[Hash].pure[F]
          case true =>
            Files[F]
              .readAll(sidecar, 4096, Flags.Read)
              .through(text.utf8.decode)
              .compile
              .string
              .attempt
              .flatMap {
                case Right(content) =>
                  parseSidecar(content) match {
                    case Some((parsedOrdinal, hash, _)) if parsedOrdinal === ordinal => hash.some.pure[F]
                    case _                                                           =>
                      // Parse failed or ordinal disagrees with filename: refuse to emit ETag.
                      sidecarLogger
                        .warn(s"Sidecar for ordinal ${ordinal.value.value} failed validation; ignoring")
                        .as(none[Hash])
                  }
                case Left(t) =>
                  sidecarLogger.warn(t)(s"Failed to read sidecar for ordinal ${ordinal.value.value}; treating as miss").as(none[Hash])
              }
        }
    }
  }

  /** Best-effort sidecar removal. Mirrors the swallow-on-error policy of `writeSidecar` -- the worst case is an orphan sidecar that the
    * read path already discards via the checkpoint-existence guard.
    */
  private def deleteSidecar(ordinal: SnapshotOrdinal): F[Unit] =
    delete(s"${ordinal.value.value}$sidecarSuffix").handleErrorWith { t =>
      sidecarLogger.warn(t)(s"Failed to delete sidecar for ordinal ${ordinal.value.value}; orphan tolerated by readSidecar guard")
    }

  /** Stream the on-disk checkpoint file directly. Returns `(contentLength, byteStream)` when the file exists. The disk-read semaphore
    * permit is acquired before the first byte and released when the stream terminates (success or cancellation), so the permit lifetime
    * tracks slow consumer drains -- the desired backpressure shape.
    *
    * No heap materialization. Previously the body was read via `.compile.to(Array)` before chunking, which allocated ~76 MB per request on
    * a 76 MB checkpoint. Streaming directly from disk lets the OS page cache (rather than a per-process Scaffeine cache) handle hot-read
    * reuse, and shrinks per-request heap pressure to chunk-size (64 KiB) times in-flight readers.
    */
  private def readBytesAsStream(ordinal: SnapshotOrdinal): F[Option[(Long, Stream[F, Byte])]] = {
    val file = path / ordinal.value.value.toString
    Files[F].exists(file).flatMap {
      case false => none[(Long, Stream[F, Byte])].pure[F]
      case true =>
        Files[F].size(file).map { size =>
          val body: Stream[F, Byte] =
            Stream
              .resource(concurrentStreams.permit)
              .flatMap(_ => Files[F].readAll(file, 64 * 1024, Flags.Read))
          (size, body).some
        }
    }
  }

  /** ETag value for the immutable identity `(ordinal, snapshotHash)`. The HTTP strong-validator semantics demand that distinct bytes
    * produce distinct ETag values; ordinal alone is insufficient because the same ordinal can carry different bytes across forks. Encoding
    * both halves ensures a stale (ord, H1) cache cannot 304 against the canonical (ord, H2).
    */
  def etagFor(ordinal: SnapshotOrdinal, snapshotHash: Hash): EntityTag =
    EntityTag(s"${ordinal.value.value}-${snapshotHash.value}", EntityTag.Strong)

  def getAsHttpResponse(ordinal: SnapshotOrdinal): F[Option[Response[F]]] =
    readBytesAsStream(ordinal).flatMap {
      case None               => none[Response[F]].pure[F]
      case Some((size, body)) =>
        // Resolve the ETag hash via `getCachedHash` so callers that invoke `getAsHttpResponse`
        // directly (without a prior route-level `getCachedHash` prefetch) still benefit from the
        // on-disk sidecar fallback after a cold restart. Without this, ord-N served from a fresh
        // process would emit no ETag and force a fresh full-body fetch on every peer poll, the
        // 304-starvation case the sidecar was added to fix. Sidecar misses (legacy checkpoints,
        // orphan state) fall through to no-ETag emission -- strictly correct, just unoptimized.
        getCachedHash(ordinal).map { maybeHash =>
          val baseHeaders = Headers(
            `Content-Type`(MediaType.application.json),
            `Content-Length`(size)
          )
          val headers = maybeHash.fold(baseHeaders)(hash => baseHeaders.put(ETag(etagFor(ordinal, hash))))
          Response[F](status = Status.Ok, headers = headers, body = body).some
        }
    }

  def getAsStream(ordinal: SnapshotOrdinal): F[Option[Stream[F, Byte]]] =
    readBytesAsStream(ordinal).map(_.map { case (_, body) => body })

  def exists(ordinal: SnapshotOrdinal): F[Boolean] =
    exists(toOrdinalName(ordinal))

  private def cleanupOldCombinedSnapshots(): F[Unit] =
    listStoredOrdinals.flatMap { ordinalsStream =>
      ordinalsStream.compile.toList.flatMap { ordinals =>
        val sortedOrdinals = ordinals.sorted(Ordering[SnapshotOrdinal].reverse)
        if (sortedOrdinals.length > maxCheckpointsStored) {
          val ordinalsToDelete = sortedOrdinals.drop(maxCheckpointsStored)
          // Delete both the checkpoint payload and its sidecar; deleteSidecar swallows
          // not-found errors so missing sidecars (legacy or already-cleaned) don't fail
          // the sweep.
          ordinalsToDelete.traverse_(o => delete(o) >> deleteSidecar(o))
        } else Concurrent[F].unit
      }
    }

  def tryWrite(ordinal: SnapshotOrdinal, snapshot: Signed[S], state: SI, snapshotHash: Hash): F[Unit] =
    lastSnapshotInfo.get.flatMap { last =>
      val shouldUpdate = snapshot.epochProgress.value.value % checkpointIntervalEpochs === 0 || last === LastCheckpointInfo.empty()
      if (shouldUpdate) {
        writeJsonTupleStream(ordinal, snapshot, state) >>
          // Sidecar after the body write: if we crash between the two, readSidecar will
          // simply miss (the checkpoint-existence guard tolerates orphan checkpoints by
          // returning None, i.e. the cold-restart no-ETag fallback). Writing in the other
          // order would risk a sidecar pointing at a partially-written body.
          Files[F].size(path / ordinal.value.value.toString).flatMap(size => writeSidecar(ordinal, snapshotHash, size)) >>
          cleanupOldCombinedSnapshots() >>
          // Populate hashCache so per-ordinal HTTP responses can emit the (ord, hash) ETag.
          // Cleanup may evict entries older than `maxCheckpointsStored`; the cache's own
          // capacity bound mirrors that retention so memory stays bounded.
          Async[F].delay(hashCache.put(ordinal, snapshotHash)) >>
          lastSnapshotInfo.set(LastCheckpointInfo(ordinal, snapshot.epochProgress, snapshotHash))
      } else {
        Concurrent[F].unit
      }
    }

  def delete(ordinal: SnapshotOrdinal): F[Unit] =
    delete(toOrdinalName(ordinal)) >> deleteSidecar(ordinal)

  def listStoredOrdinals: F[Stream[F, SnapshotOrdinal]] =
    listFiles.map {
      _.map(_.name)
        .map(_.toLongOption)
        .map(_.flatMap(SnapshotOrdinal(_)))
        .collect { case Some(a) => a }
    }

  def deleteAbove(ordinal: SnapshotOrdinal): F[Unit] =
    listStoredOrdinals.flatMap {
      _.filter(_ > ordinal).evalMap(delete).compile.drain
    }

  def getLatestOrdinal: F[Option[SnapshotOrdinal]] =
    listStoredOrdinals.flatMap { ordinalsStream =>
      ordinalsStream.compile.toList.map { ordinals =>
        if (ordinals.isEmpty) None
        else Some(ordinals.max)
      }
    }

  def getLatestAsHttpResponse: F[Option[Response[F]]] =
    getLatestOrdinal.flatMap {
      case Some(ordinal) => getAsHttpResponse(ordinal)
      case None          => Concurrent[F].pure(None)
    }

  def getLatestAsStream: F[Option[Stream[F, Byte]]] =
    getLatestOrdinal.flatMap {
      case Some(ordinal) => getAsStream(ordinal)
      case None          => Concurrent[F].pure(None)
    }

  def getLatestCheckpointInfo: F[LastCheckpointInfo] =
    lastSnapshotInfo.get

  /** Look up the snapshot-hash for a given ordinal. Resolution order:
    *   1. in-memory `hashCache` (populated at write time); 2. on-disk sidecar (durable across process restart) -- on hit, repopulate the
    *      cache so subsequent reads stay in memory.
    *
    * Returns `None` when neither source can produce a hash. Used by the route layer to construct an `(ord, hash)` ETag when available. The
    * sidecar fallback is the fix for the cold-restart 304 starvation case: previously every peer polling `/latest/combined/stream` after a
    * restart got a fresh 100MB body served instead of a 304 Not Modified.
    */
  def getCachedHash(ordinal: SnapshotOrdinal): F[Option[Hash]] =
    Async[F].delay(hashCache.getIfPresent(ordinal)).flatMap {
      case s @ Some(_) => (s: Option[Hash]).pure[F]
      case None =>
        readSidecar(ordinal).flatTap {
          case Some(hash) => Async[F].delay(hashCache.put(ordinal, hash))
          case None       => Concurrent[F].unit
        }
    }

  /** Read the on-disk byte size of the checkpoint for `ordinal`. Returns `None` when the checkpoint is absent.
    *
    * Used by the per-IP bandwidth limiter to make a pre-response accept/reject decision: if the request's IP is already close to its window
    * budget and this estimator returns a value that would push the IP over the cap, the limiter rejects with 429 BEFORE the heavy route
    * handler is invoked. The `Files.size` query is cheap (a `stat` call) and avoids the cost of building the full response only to drain
    * it.
    */
  def getCheckpointSize(ordinal: SnapshotOrdinal): F[Option[Long]] = {
    val file = path / ordinal.value.value.toString
    Files[F].exists(file).flatMap {
      case false => none[Long].pure[F]
      case true  => Files[F].size(file).map(_.some)
    }
  }

  private def toOrdinalName(ordinal: SnapshotOrdinal): String =
    ordinal.value.value.toString
}

object CombinedSnapshotCheckpointFileSystemStorage {

  /** Hash cache keyed by ordinal. Populated at write time so the per-ordinal HTTP route can emit a strong-validator ETag of `(ordinal,
    * snapshotHash)`. Capacity matches `maxCheckpointsStored` (default 2) plus a small slack -- historical reads beyond that window fall
    * back to no-ETag emission.
    */
  private def mkHashCache: Cache[SnapshotOrdinal, Hash] =
    Scaffeine()
      .expireAfterWrite(5.minutes)
      .maximumSize(8)
      .build[SnapshotOrdinal, Hash]()

  def make[
    F[_]: Async: Files,
    S <: Snapshot,
    SI <: SnapshotInfo[_]
  ](
    path: Path
  )(implicit encSigned: Encoder[Signed[S]], encState: Encoder[SI]): F[CombinedSnapshotCheckpointFileSystemStorage[F, S, SI]] = for {
    lastCheckpointInfo <- SignallingRef.of[F, LastCheckpointInfo](LastCheckpointInfo.empty())
    // Default 4 aligned with the route-scoped heavy-serve cap so the storage layer does not
    // become the bottleneck before the route-scoped 503-fast-fail can kick in.
    concurrentStreams <- Semaphore[F](4)
    hashes = mkHashCache
    storage = new CombinedSnapshotCheckpointFileSystemStorage[F, S, SI](path, lastCheckpointInfo, concurrentStreams, hashes)
    _ <- storage.createDirectoryIfNotExists().rethrowT
  } yield storage
}
