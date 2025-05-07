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

  private val logger = Slf4jLogger.getLoggerFromName[F]("SnapshotLocalFileSystemStorage")

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

  def delete(ordinal: SnapshotOrdinal): F[Unit] =
    delete(toOrdinalName(ordinal))

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
          }.handleErrorWith { ex =>
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
  )(implicit hs: HasherSelector[F], kryoSerializer: KryoSerializer[F]): F[Unit] =
    chunk
      .map(file => file.name.toLongOption.flatMap(SnapshotOrdinal(_)))
      .collect { case Some(fileOrdinal) => fileOrdinal }
      .parEvalMapUnordered(maxParallelFileOperations) { fileOrdinal =>
        val operation = for {
          snapshotOpt <- read(fileOrdinal)
          _ <- snapshotOpt match {
            case Some(snapshot) =>
              HasherSelector[F]
                .forOrdinal(snapshot.ordinal) { implicit hasher =>
                  for {
                    hashed <- snapshot.toHashed
                    _ <- movePersistedToTmp(hashed.hash, hashed.ordinal).handleErrorWith { err =>
                      logger.warn(err)(s"Failed to move persisted to tmp for ordinal=${snapshot.ordinal}, hash=${hashed.hash}")
                      Async[F].raiseError(err)
                    }
                  } yield ()
                }
                .handleErrorWith { error =>
                  implicit val kryoHasher = Hasher.forKryo[F]
                  logger.warn(error)(s"cleanupAbove failed for ordinal=${snapshot.ordinal}, retrying with Kryo hasher") >>
                    snapshot.toHashed.flatMap { s =>
                      movePersistedToTmp(s.hash, s.ordinal).handleErrorWith { err =>
                        logger.error(err)(s"Failed to move persisted to tmp even with Kryo hasher for ordinal=${snapshot.ordinal}") >>
                        Async[F].raiseError(err)
                      }
                    }
                }
            case None =>
              logger.debug(s"No snapshot found for ordinal $fileOrdinal") >> Async[F].unit
          }
        } yield ()

        operation.handleErrorWith { err =>
          logger.error(err)(s"Failed to process file with ordinal $fileOrdinal") >>
            Async[F].unit
        }
      }
      .compile
      .drain

  def cleanupAboveOrdinal(
    ordinal: SnapshotOrdinal,
    movePersistedToTmp: (Hash, SnapshotOrdinal) => F[Unit]
  )(implicit hs: HasherSelector[F], kryoSerializer: KryoSerializer[F]): F[Unit] = for {
    _ <- logger.info(s"Searching for persisted files above ordinal ${ordinal.show}")
    baseDirectory = (ordinal.value.value / ordinalChunkSize.value) * ordinalChunkSize.value

    _ <- baseDirectory.tailRecM { currentBase =>
      for {
        baseDir <- dir.map(_ / "ordinal" / currentBase.toString)
        result <-
          if (!baseDir.exists) {
            ().asRight[Long].pure
          } else {
            for {
              _ <- logger.info(s"Processing directory for base $currentBase")

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
              }.handleErrorWith { ex =>
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
