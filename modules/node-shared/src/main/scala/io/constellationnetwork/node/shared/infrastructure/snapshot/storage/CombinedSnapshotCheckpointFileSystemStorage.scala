package io.constellationnetwork.node.shared.infrastructure.snapshot.storage

import java.io.{FileOutputStream, OutputStreamWriter}

import cats.effect.std.Semaphore
import cats.effect.{Async, Concurrent, Resource}
import cats.syntax.all._

import io.constellationnetwork.schema._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.storage.LocalFileSystemStorage

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
  concurrentStreams: Semaphore[F]
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

  def getAsHttpResponse(ordinal: SnapshotOrdinal): F[Option[Response[F]]] = {
    val file = path / ordinal.value.value.toString

    Files[F].exists(file).flatMap {
      case false => Concurrent[F].pure(None)
      case true =>
        val fileStream: Stream[F, Byte] = Files[F].readAll(file, 64 * 1024, Flags.Read)
        val bodyWithPermit: Stream[F, Byte] =
          Stream.resource(concurrentStreams.permit).flatMap { _ =>
            fileStream
          }

        // Chunked/streamed response: intentionally no Content-Length. The body is
        // streamed lazily from disk and clients rarely drain the whole file, so a
        // full-file Content-Length both violated RFC 7230 (must not accompany
        // Transfer-Encoding) and made the response-size metric attribute the entire
        // file per connection.
        Response[F](
          status = Status.Ok,
          headers = Headers(
            `Content-Type`(MediaType.application.json),
            `Transfer-Encoding`(TransferCoding.chunked)
          ),
          body = bodyWithPermit
        ).some.pure[F]
    }
  }

  def getAsStream(ordinal: SnapshotOrdinal): F[Option[Stream[F, Byte]]] = {
    val file = path / ordinal.value.value.toString
    Files[F].exists(file).flatMap {
      case false => Concurrent[F].pure(None)
      case true =>
        val fileStream: Stream[F, Byte] =
          Stream.resource(concurrentStreams.permit).flatMap { _ =>
            Files[F].readAll(file, 64 * 1024, Flags.Read)
          }
        fileStream.some.pure[F]
    }
  }

  def exists(ordinal: SnapshotOrdinal): F[Boolean] =
    exists(toOrdinalName(ordinal))

  private def cleanupOldCombinedSnapshots(): F[Unit] =
    listStoredOrdinals.flatMap { ordinalsStream =>
      ordinalsStream.compile.toList.flatMap { ordinals =>
        val sortedOrdinals = ordinals.sorted(Ordering[SnapshotOrdinal].reverse)
        if (sortedOrdinals.length > maxCheckpointsStored) {
          val ordinalsToDelete = sortedOrdinals.drop(maxCheckpointsStored)
          ordinalsToDelete.traverse_(delete)
        } else Concurrent[F].unit
      }
    }

  def tryWrite(ordinal: SnapshotOrdinal, snapshot: Signed[S], state: SI, snapshotHash: Hash): F[Unit] =
    lastSnapshotInfo.get.flatMap { last =>
      val shouldUpdate = snapshot.epochProgress.value.value % checkpointIntervalEpochs === 0 || last === LastCheckpointInfo.empty()
      if (shouldUpdate) {
        writeJsonTupleStream(ordinal, snapshot, state) >>
          cleanupOldCombinedSnapshots() >>
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
      _.filter(_ > ordinal)
        .evalMap(delete)
        .compile
        .drain
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

  private def toOrdinalName(ordinal: SnapshotOrdinal): String =
    ordinal.value.value.toString
}

object CombinedSnapshotCheckpointFileSystemStorage {

  def make[
    F[_]: Async: Files,
    S <: Snapshot,
    SI <: SnapshotInfo[_]
  ](
    path: Path
  )(implicit encSigned: Encoder[Signed[S]], encState: Encoder[SI]): F[CombinedSnapshotCheckpointFileSystemStorage[F, S, SI]] = for {
    lastCheckpointInfo <- SignallingRef.of[F, LastCheckpointInfo](LastCheckpointInfo.empty())
    concurrentStreams <- Semaphore[F](5)
    storage = new CombinedSnapshotCheckpointFileSystemStorage[F, S, SI](path, lastCheckpointInfo, concurrentStreams)
    _ <- storage.createDirectoryIfNotExists().rethrowT
  } yield storage
}
