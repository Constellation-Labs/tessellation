package io.constellationnetwork.node.shared.infrastructure.snapshot.storage

import cats.Applicative
import cats.effect.Async
import cats.syntax.all._

import scala.util.control.NoStackTrace

import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencyIncrementalSnapshotV1}
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.SnapshotLocalFileSystemStorage.UnableToPersistSnapshot
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.snapshot.Snapshot
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, HasherSelector}
import io.constellationnetwork.storage.PathGenerator._
import io.constellationnetwork.storage.{PathGenerator, SerializableLocalFileSystemStorage}

import better.files.File
import fs2.Stream
import fs2.io.file.Path
import io.circe.{Decoder, Encoder}
import io.estatico.newtype.ops._
import org.typelevel.log4cats.slf4j.Slf4jLogger

abstract class SnapshotLocalFileSystemStorage[
  F[_]: Async: JsonSerializer,
  S <: Snapshot: Encoder: Decoder
](
  path: Path
) extends SerializableLocalFileSystemStorage[F, Signed[S]](path) {

  val ordinalChunkSize = ChunkSize(20000)
  val hashPathGenerator = PathGenerator.forHash(Depth(2), PrefixSize(3))
  val ordinalPathGenerator = PathGenerator.forOrdinal(ordinalChunkSize)
  val maxParallelFileOperations = 4

  override val logger = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

  def write(snapshot: Signed[S])(implicit hasher: Hasher[F]): F[Unit] = {
    val ordinalName = toOrdinalName(snapshot.value)

    toHashName(snapshot.value).flatMap { hashName =>
      (exists(ordinalName), exists(hashName)).flatMapN { (ordinalExists, hashExists) =>
        for {
          _ <- UnableToPersistSnapshot(ordinalName, hashName, hashExists).raiseError[F, Unit].whenA(ordinalExists)
          _ <- hashExists
            .pure[F]
            .ifM(
              logger.warn(s"Snapshot hash file $hashName exists but ordinal missing; linking to $ordinalName"),
              write(hashName, snapshot)
            )
          _ <- link(hashName, ordinalName)
        } yield ()
      }
    }
  }

  def writeUnderOrdinal(snapshot: Signed[S]): F[Unit] = {
    val ordinalName = toOrdinalName(snapshot.value)

    write(ordinalName, snapshot)
  }

  def read(ordinal: SnapshotOrdinal): F[Option[Signed[S]]] =
    read(toOrdinalName(ordinal))

  def read(hash: Hash): F[Option[Signed[S]]] =
    read(toHashName(hash))

  def exists(ordinal: SnapshotOrdinal): F[Boolean] =
    exists(toOrdinalName(ordinal))

  def exists(hash: Hash): F[Boolean] =
    exists(toHashName(hash))

  /** Prove that the ordinal and content-addressed indexes identify the same snapshot, repairing only the torn-write case where the
    * content-addressed file is valid but its ordinal hardlink is absent.
    *
    * Snapshot persistence is intentionally two-indexed: `hash/.../<hash>` owns the bytes and `ordinal/.../<ordinal>` is a hardlink. A
    * process stop between moving the bytes under the hash and creating the hardlink leaves `exists(hash) == true` while `read(ordinal) ==
    * None`. Treating the hash path alone as a replay anchor then stops a backward chain walk at an anchor that forward replay cannot read.
    *
    * This repair is deliberately narrow:
    *   - the decoded snapshot must carry `expectedOrdinal`;
    *   - hashing those bytes must produce `expectedHash`;
    *   - an occupied ordinal is never overwritten here (fork replacement remains the validated download replay's job);
    *   - the new hardlink is read back and re-verified before the result is reported usable.
    *
    * No snapshot bytes are synthesized or accepted by this helper. Callers remain responsible for their normal signature, checkpoint, and
    * state-proof validation before using the snapshot as consensus/application state.
    */
  def ensureOrdinalLink(expectedHash: Hash, expectedOrdinal: SnapshotOrdinal)(
    implicit hasher: Hasher[F]
  ): F[SnapshotLocalFileSystemStorage.OrdinalLinkStatus] = {
    import SnapshotLocalFileSystemStorage.OrdinalLinkStatus

    def identify(snapshot: Signed[S]): F[(SnapshotOrdinal, Hash)] =
      snapshot.toHashed.map(hashed => (hashed.ordinal, hashed.hash))

    def exact(snapshot: Signed[S]): F[Boolean] =
      identify(snapshot).map { case (ordinal, hash) => ordinal === expectedOrdinal && hash === expectedHash }

    read(expectedOrdinal).flatMap {
      case Some(snapshot) =>
        identify(snapshot).flatMap {
          case (ordinal, hash) if ordinal === expectedOrdinal && hash === expectedHash =>
            read(expectedHash).flatMap {
              case Some(hashSnapshot) =>
                identify(hashSnapshot).map {
                  case (hashOrdinal, hashValue) if hashOrdinal === expectedOrdinal && hashValue === expectedHash =>
                    OrdinalLinkStatus.Linked
                  case (hashOrdinal, hashValue) => OrdinalLinkStatus.HashContentMismatch(hashOrdinal, hashValue)
                }
              case None =>
                exists(expectedHash).map {
                  case true  => OrdinalLinkStatus.HashUnreadable
                  case false => OrdinalLinkStatus.HashIndexMissing
                }
            }
          case (ordinal, hash) => OrdinalLinkStatus.OrdinalOccupied(ordinal, hash).pure[F].widen
        }
      case None =>
        exists(expectedHash).ifM(
          read(expectedHash).flatMap {
            case Some(snapshot) =>
              exact(snapshot).ifM(
                write(snapshot).handleErrorWith {
                  // Another repair/download fiber may have installed the ordinal after our initial
                  // read. Re-read and verify that winner; every other filesystem error remains loud.
                  case _: UnableToPersistSnapshot => Applicative[F].unit
                  case error                      => error.raiseError[F, Unit]
                } >> read(expectedOrdinal).flatMap {
                  case Some(relinked) =>
                    exact(relinked).map {
                      case true  => OrdinalLinkStatus.Repaired
                      case false => OrdinalLinkStatus.RepairIncomplete
                    }
                  case None => OrdinalLinkStatus.RepairIncomplete.pure[F].widen
                },
                identify(snapshot).map { case (ordinal, hash) => OrdinalLinkStatus.HashContentMismatch(ordinal, hash) }
              )
            case None => OrdinalLinkStatus.HashUnreadable.pure[F].widen
          },
          OrdinalLinkStatus.Missing.pure[F].widen
        )
    }
  }

  def delete(ordinal: SnapshotOrdinal): F[Unit] =
    delete(toOrdinalName(ordinal))

  def delete(hash: Hash): F[Unit] =
    delete(toHashName(hash))

  def getPath(hash: Hash): F[File] =
    getPath(toHashName(hash))

  def getPath(snapshot: Signed[S])(implicit hasher: Hasher[F]): F[File] =
    toHashName(snapshot.value).flatMap { hashName =>
      getPath(hashName)
    }

  def move(hash: Hash, to: File): F[Unit] =
    move(toHashName(hash), to)

  def move(snapshot: Signed[S], to: File)(implicit hasher: Hasher[F]): F[Unit] =
    toHashName(snapshot.value).flatMap { hashName =>
      move(hashName, to)
    }

  def moveByOrdinal(snapshot: Signed[S], to: File): F[Unit] =
    move(toOrdinalName(snapshot), to)

  def link(snapshot: Signed[S])(implicit hasher: Hasher[F]): F[Unit] =
    toHashName(snapshot).flatMap { hashName =>
      link(hashName, toOrdinalName(snapshot))
    }

  def findAbove(ordinal: SnapshotOrdinal): Stream[F, File] = {
    val baseDirectory = (ordinal.value.value / ordinalChunkSize.value) * ordinalChunkSize.value

    def isAbove(file: File): Boolean =
      file.name.toLongOption.exists(_ > ordinal.value.value)

    def listFilesFrom(base: Long): F[Stream[F, File]] =
      dir
        .map(_ / "ordinal" / base.toString)
        .flatMap { baseDir =>
          Async[F].blocking {
            if (base == baseDirectory)
              baseDir.list(f => !f.isDirectory && isAbove(f), maxDepth = 1)
            else
              baseDir.list(f => !f.isDirectory, maxDepth = 1)
          }.handleErrorWith {
            case _: java.nio.file.NoSuchFileException =>
              // Directory doesn't exist or was removed - not an error
              Async[F].pure(Iterator.empty)
            case ex =>
              logger.warn(ex)(s"Error listing files in directory ${baseDir.pathAsString}") >>
                Async[F].pure(Iterator.empty)
          }
        }
        .map(_.toList)
        .map(Stream.emits(_))

    Stream
      .unfoldEval[F, Long, List[File]](baseDirectory) { currentBase =>
        val currentDirF = dir.map(_ / "ordinal" / currentBase.toString)

        currentDirF.flatMap { currentDir =>
          Async[F].blocking(currentDir.exists).flatMap {
            case false => Async[F].pure(None)
            case true =>
              listFilesFrom(currentBase).flatMap { stream =>
                stream.compile.toList.map { files =>
                  Some((files, currentBase + ordinalChunkSize.value.toLong))
                }
              }
          }
        }
      }
      .flatMap(files => Stream.emits(files))
  }

  def processFileChunk(
    chunk: Stream[F, File],
    movePersistedToTmp: (Hash, SnapshotOrdinal) => F[Unit]
  )(implicit hs: HasherSelector[F]): F[Unit] =
    chunk
      .map(file => file.name.toLongOption.flatMap(SnapshotOrdinal(_)))
      .collect { case Some(fileOrdinal) => fileOrdinal }
      .parEvalMapUnordered(maxParallelFileOperations) { fileOrdinal =>
        val operation = for {
          snapshotOpt <- read(fileOrdinal)
          _ <- snapshotOpt match {
            case Some(snapshot) =>
              HasherSelector[F].withCurrent { implicit hasher =>
                for {
                  hashed <- snapshot.toHashed
                  _ <- movePersistedToTmp(hashed.hash, hashed.ordinal).handleErrorWith {
                    case _: java.nio.file.NoSuchFileException =>
                      // File already moved/deleted - this is expected during concurrent cleanup
                      logger.debug(s"File already removed during cleanup for ordinal=${snapshot.ordinal}")
                    case err =>
                      logger.warn(err)(
                        s"Failed to move persisted to tmp for ordinal=${snapshot.ordinal}, hash=${hashed.hash}"
                      ) >>
                        Async[F].raiseError(err)
                  }
                } yield ()
              }
            case None =>
              logger.debug(s"No snapshot found for ordinal $fileOrdinal") >> Async[F].unit
          }
        } yield ()

        operation.handleErrorWith {
          case _: java.nio.file.NoSuchFileException =>
            // File was deleted between listing and processing - expected during cleanup
            Async[F].unit
          case err =>
            logger.warn(err)(s"Failed to process file with ordinal $fileOrdinal") >>
              Async[F].unit
        }
      }
      .compile
      .drain

  def cleanupAboveOrdinal(
    ordinal: SnapshotOrdinal,
    movePersistedToTmp: (Hash, SnapshotOrdinal) => F[Unit]
  )(implicit hs: HasherSelector[F]): F[Unit] = for {
    _ <- logger.debug(s"Searching for persisted files above ordinal ${ordinal.show}")
    baseDirectory = (ordinal.value.value / ordinalChunkSize.value) * ordinalChunkSize.value

    _ <- baseDirectory.tailRecM { currentBase =>
      for {
        baseDir <- dir.map(_ / "ordinal" / currentBase.toString)
        baseDirExists <- Async[F].blocking(baseDir.exists)
        result <-
          if (!baseDirExists) {
            ().asRight[Long].pure
          } else {
            for {
              _ <- logger.debug(s"Processing directory for base $currentBase")

              files <- Async[F].blocking {
                if (currentBase == baseDirectory)
                  baseDir
                    .list(
                      f => !f.isDirectory && f.name.toLongOption.exists(_ > ordinal.value.value),
                      maxDepth = 1
                    )
                    .toList
                else
                  baseDir.list(f => !f.isDirectory, maxDepth = 1).toList
              }.handleErrorWith {
                case _: java.nio.file.NoSuchFileException =>
                  // Directory was removed - not an error during cleanup
                  Async[F].pure(List.empty)
                case ex =>
                  logger.warn(ex)(s"Error listing files in directory ${baseDir.pathAsString}") >>
                    Async[F].pure(List.empty)
              }.map(Stream.emits(_))

              _ <- processFileChunk(files, movePersistedToTmp)
            } yield (currentBase + ordinalChunkSize.value).asLeft[Unit]
          }
      } yield result
    }
  } yield ()

  private def toOrdinalName(snapshot: S): String = toOrdinalName(snapshot.ordinal)

  private def toOrdinalName(ordinal: SnapshotOrdinal): String =
    "ordinal/" + ordinalPathGenerator.get(ordinal.value.value.toString)

  private def toHashName(snapshot: S)(implicit hasher: Hasher[F]): F[String] = snapshot.hash.map(toHashName)

  private def toHashName(hash: Hash): String =
    "hash/" + hashPathGenerator.get(hash.coerce[String])

}

object SnapshotLocalFileSystemStorage {

  sealed trait OrdinalLinkStatus {
    def label: String
    def usable: Boolean = false
  }

  object OrdinalLinkStatus {
    case object Linked extends OrdinalLinkStatus {
      val label: String = "linked"
      override val usable: Boolean = true
    }
    case object Repaired extends OrdinalLinkStatus {
      val label: String = "repaired"
      override val usable: Boolean = true
    }
    case object Missing extends OrdinalLinkStatus { val label: String = "missing" }
    case object HashIndexMissing extends OrdinalLinkStatus { val label: String = "hash_index_missing" }
    case object HashUnreadable extends OrdinalLinkStatus { val label: String = "hash_unreadable" }
    case object RepairIncomplete extends OrdinalLinkStatus { val label: String = "repair_incomplete" }
    final case class OrdinalOccupied(actualOrdinal: SnapshotOrdinal, actualHash: Hash) extends OrdinalLinkStatus {
      val label: String = "ordinal_occupied"
    }
    final case class HashContentMismatch(actualOrdinal: SnapshotOrdinal, actualHash: Hash) extends OrdinalLinkStatus {
      val label: String = "hash_content_mismatch"
    }
  }

  case class UnableToPersistSnapshot(ordinalName: String, hashName: String, hashFileExists: Boolean) extends NoStackTrace {
    override val getMessage: String = s"Ordinal $ordinalName exists. File $hashName exists: $hashFileExists."
  }

}

object GlobalSnapshotLocalFileSystemStorage {

  def make[F[_]: Async: KryoSerializer: JsonSerializer](
    path: Path
  ): F[SnapshotLocalFileSystemStorage[F, GlobalSnapshot]] =
    Applicative[F]
      .pure(new SnapshotLocalFileSystemStorage[F, GlobalSnapshot](path) {
        def deserializeFallback(bytes: Array[Byte]): Either[Throwable, Signed[GlobalSnapshot]] =
          KryoSerializer[F].deserialize[Signed[GlobalSnapshot]](bytes)
      })
      .flatTap { storage =>
        storage.createDirectoryIfNotExists().rethrowT
      }
}

object GlobalIncrementalSnapshotLocalFileSystemStorage {

  def make[F[_]: Async: KryoSerializer: JsonSerializer](
    path: Path
  ): F[SnapshotLocalFileSystemStorage[F, GlobalIncrementalSnapshot]] =
    Applicative[F]
      .pure(new SnapshotLocalFileSystemStorage[F, GlobalIncrementalSnapshot](path) {
        def deserializeFallback(bytes: Array[Byte]): Either[Throwable, Signed[GlobalIncrementalSnapshot]] =
          KryoSerializer[F].deserialize[Signed[GlobalIncrementalSnapshotV1]](bytes).map(_.map(_.toGlobalIncrementalSnapshot))
      })
      .flatTap { storage =>
        storage.createDirectoryIfNotExists().rethrowT
      }
}

object CurrencyIncrementalSnapshotLocalFileSystemStorage {

  def make[F[_]: Async: KryoSerializer: JsonSerializer](
    path: Path
  ): F[SnapshotLocalFileSystemStorage[F, CurrencyIncrementalSnapshot]] =
    Applicative[F]
      .pure(new SnapshotLocalFileSystemStorage[F, CurrencyIncrementalSnapshot](path) {
        def deserializeFallback(bytes: Array[Byte]): Either[Throwable, Signed[CurrencyIncrementalSnapshot]] =
          KryoSerializer[F].deserialize[Signed[CurrencyIncrementalSnapshotV1]](bytes).map(_.map(_.toCurrencyIncrementalSnapshot))
      })
      .flatTap { storage =>
        storage.createDirectoryIfNotExists().rethrowT
      }
}
