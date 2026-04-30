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
import fs2.Stream
import fs2.concurrent.SignallingRef
import fs2.io.file.{Files, Flags, Path}
import io.circe.syntax._
import io.circe.{Encoder, Printer}
import org.http4s._
import org.http4s.headers._

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
  concurrentStreams: Semaphore[F],
  byteCache: Cache[SnapshotOrdinal, Array[Byte]],
  // Parallel cache populated at write time so per-ordinal requests can emit a strong-validator
  // ETag of `<ordinal>-<snapshotHash>` rather than ordinal-only. The ordinal-only form would be
  // a lying validator: ord-N can map to different bytes on different forks, and a peer holding
  // stale (N, H₁) querying with `If-None-Match: "N"` would falsely 304 against the canonical
  // (N, H₂). Snapshots loaded from disk after a restart that aren't in this cache fall back to
  // emitting no ETag — strictly correct (always 200), just no optimization for that ordinal.
  hashCache: Cache[SnapshotOrdinal, Hash]
)(
  implicit encSigned: Encoder[Signed[S]],
  encState: Encoder[SI]
) extends LocalFileSystemStorage[F, Array[Byte]](path) {
  // These aren't necessary on pureconfig, because in theory they should never change, so I'll hardcode
  private val maxCheckpointsStored = 2
  private val checkpointIntervalEpochs = 5

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

  /** Read checkpoint bytes — serves from Scaffeine cache if present, otherwise reads from disk and populates cache. Max 4 entries (~8MB for
    * 2MB checkpoints) with 60s TTL.
    */
  private def readBytesWithCache(ordinal: SnapshotOrdinal): F[Option[Array[Byte]]] =
    Async[F].delay(byteCache.getIfPresent(ordinal)).flatMap {
      case Some(bytes) => bytes.some.pure[F]
      case None =>
        val file = path / ordinal.value.value.toString
        Files[F].exists(file).flatMap {
          case false => none[Array[Byte]].pure[F]
          case true =>
            Stream
              .resource(concurrentStreams.permit)
              .flatMap { _ =>
                Files[F].readAll(file, 64 * 1024, Flags.Read)
              }
              .compile
              .to(Array)
              .flatTap { bytes =>
                Async[F].delay(byteCache.put(ordinal, bytes))
              }
              .map(_.some)
        }
    }

  /** Wrap a cached `Array[Byte]` as a chunked fs2 stream so http4s/netty can flush each segment as the socket drains rather than queueing
    * the entire response in the outbound buffer. The chunks are views into the same underlying array (no copying), so the cache benefit is
    * preserved.
    *
    * Background (2026-04-28 testnet): with state sizes ~58 MB, the prior single-chunk pattern caused the netty outbound buffer to hold the
    * full response for slow consumers, manifesting as multi-GB direct-buffer pressure (8.99 GB observed on the rollback validator under
    * steady 1.4 req/s traffic at this endpoint). Chunked emission caps per-stream buffer pressure to roughly `chunkSize ×
    * in-flight-responses`.
    */
  private def chunkedBodyStream(bytes: Array[Byte]): Stream[F, Byte] =
    Stream.unfoldChunk(0) { offset =>
      if (offset >= bytes.length) None
      else {
        val len = math.min(64 * 1024, bytes.length - offset)
        Some((fs2.Chunk.array(bytes, offset, len), offset + len))
      }
    }

  /** ETag value for the immutable identity `(ordinal, snapshotHash)`. The HTTP strong-validator semantics demand that distinct bytes
    * produce distinct ETag values; ordinal alone is insufficient because the same ordinal can carry different bytes across forks. Encoding
    * both halves ensures a stale (ord, H₁) cache cannot 304 against the canonical (ord, H₂).
    */
  def etagFor(ordinal: SnapshotOrdinal, snapshotHash: Hash): EntityTag =
    EntityTag(s"${ordinal.value.value}-${snapshotHash.value}", EntityTag.Strong)

  def getAsHttpResponse(ordinal: SnapshotOrdinal): F[Option[Response[F]]] =
    readBytesWithCache(ordinal).map(_.map { bytes =>
      // Emit ETag only when we have a cached hash for this ordinal (populated at write time).
      // Cold-restart historical reads have no cached hash; we fall back to no-ETag → server
      // always returns 200, client gets the body. Strictly correct, just no optimization for
      // that single request — and the rarely-fetched-historical case is tolerant of that.
      val baseHeaders = Headers(
        `Content-Type`(MediaType.application.json),
        `Content-Length`(bytes.length.toLong)
      )
      val headers = hashCache
        .getIfPresent(ordinal)
        .fold(baseHeaders)(hash => baseHeaders.put(ETag(etagFor(ordinal, hash))))
      Response[F](status = Status.Ok, headers = headers, body = chunkedBodyStream(bytes))
    })

  def getAsStream(ordinal: SnapshotOrdinal): F[Option[Stream[F, Byte]]] =
    readBytesWithCache(ordinal).map(_.map(chunkedBodyStream))

  def exists(ordinal: SnapshotOrdinal): F[Boolean] =
    exists(toOrdinalName(ordinal))

  private def cleanupOldCombinedSnapshots(): F[Unit] =
    listStoredOrdinals.flatMap { ordinalsStream =>
      ordinalsStream.compile.toList.flatMap { ordinals =>
        val sortedOrdinals = ordinals.sorted(Ordering[SnapshotOrdinal].reverse)
        if (sortedOrdinals.length > maxCheckpointsStored) {
          val ordinalsToDelete = sortedOrdinals.drop(maxCheckpointsStored)
          ordinalsToDelete.traverse_ { ord =>
            Async[F].delay(byteCache.invalidate(ord)) >> delete(ord)
          }
        } else Concurrent[F].unit
      }
    }

  def tryWrite(ordinal: SnapshotOrdinal, snapshot: Signed[S], state: SI, snapshotHash: Hash): F[Unit] =
    lastSnapshotInfo.get.flatMap { last =>
      val shouldUpdate = snapshot.epochProgress.value.value % checkpointIntervalEpochs === 0 || last === LastCheckpointInfo.empty()
      if (shouldUpdate) {
        writeJsonTupleStream(ordinal, snapshot, state) >>
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
    delete(toOrdinalName(ordinal))

  def listStoredOrdinals: F[Stream[F, SnapshotOrdinal]] =
    listFiles.map {
      _.map(_.name)
        .map(_.toLongOption)
        .map(_.flatMap(SnapshotOrdinal(_)))
        .collect { case Some(a) => a }
    }

  def deleteAbove(ordinal: SnapshotOrdinal): F[Unit] =
    listStoredOrdinals.flatMap {
      _.filter(_ > ordinal).evalMap { ord =>
        Async[F].delay(byteCache.invalidate(ord)) >> delete(ord)
      }.compile.drain
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

  /** Look up the cached snapshot-hash for a given ordinal. Populated at write time; returns `None` for ordinals not in the retention window
    * (e.g. cold-restart historical reads). Used by the route layer to construct an `(ord, hash)` ETag when available.
    */
  def getCachedHash(ordinal: SnapshotOrdinal): F[Option[Hash]] =
    Async[F].delay(hashCache.getIfPresent(ordinal))

  private def toOrdinalName(ordinal: SnapshotOrdinal): String =
    ordinal.value.value.toString
}

object CombinedSnapshotCheckpointFileSystemStorage {

  /** Scaffeine byte cache for combined checkpoint files. Small max size (4 entries) since checkpoints are ~2MB each. TTL ensures stale data
    * is evicted.
    */
  private def mkByteCache: Cache[SnapshotOrdinal, Array[Byte]] =
    Scaffeine()
      .expireAfterWrite(60.seconds)
      .maximumSize(4)
      .build[SnapshotOrdinal, Array[Byte]]()

  /** Hash cache keyed by ordinal. Populated at write time so the per-ordinal HTTP route can emit a strong-validator ETag of `(ordinal,
    * snapshotHash)`. Capacity matches `maxCheckpointsStored` (default 2) plus a small slack — historical reads beyond that window fall back
    * to no-ETag emission.
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
    concurrentStreams <- Semaphore[F](5)
    cache = mkByteCache
    hashes = mkHashCache
    storage = new CombinedSnapshotCheckpointFileSystemStorage[F, S, SI](path, lastCheckpointInfo, concurrentStreams, cache, hashes)
    _ <- storage.createDirectoryIfNotExists().rethrowT
  } yield storage
}
