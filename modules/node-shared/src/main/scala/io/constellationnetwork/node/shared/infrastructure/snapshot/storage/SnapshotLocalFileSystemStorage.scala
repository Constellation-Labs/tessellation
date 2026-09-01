package io.constellationnetwork.node.shared.infrastructure.snapshot.storage

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files => JFiles, _}
import java.util.{Arrays, UUID}

import cats.Applicative
import cats.effect.Async
import cats.syntax.all._

import scala.jdk.CollectionConverters._
import scala.util.control.NoStackTrace

import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencyIncrementalSnapshotV1}
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.SnapshotLocalFileSystemStorage.UnableToPersistSnapshot
import io.constellationnetwork.node.shared.infrastructure.storage.CrashSafeAtomicFileWriter
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

  /** Atomically replace both persisted snapshot indexes with the exact validated recovery envelope. A crash between the two independent
    * atomic replacements is repaired by rerunning recovery before consensus starts.
    */
  def replaceForRecovery(snapshot: Signed[S])(implicit hasher: Hasher[F]): F[Unit] =
    for {
      bytes <- JsonSerializer[F].serialize(snapshot)
      hashName <- toHashName(snapshot.value)
      ordinalName = toOrdinalName(snapshot.value)
      previousHashName <- read(snapshot.ordinal).flatMap(_.traverse(previous => toHashName(previous.value)))
      _ <- atomicReplace(hashName, bytes)
      // A different value at the same ordinal is an abandoned branch, not an
      // immutable historical snapshot. Leaving its content-addressed index in
      // place would let peers continue serving fork bytes after recovery. Do
      // this after the new hash is durable but before replacing the ordinal:
      // if the process crashes here, the old ordinal still identifies the
      // cleanup target and a retry converges.
      _ <- previousHashName.filterNot(_ === hashName).traverse_(delete)
      _ <- previousHashName.filterNot(_ === hashName).traverse_ { previous =>
        exists(previous).flatMap(
          Async[F].raiseWhen(_)(
            new IllegalStateException(s"Recovery abandoned-hash cleanup failed: $previous")
          )
        )
      }
      _ <- atomicReplace(ordinalName, bytes)
      hashBytes <- readBytes(hashName).flatMap(
        _.liftTo[F](new IllegalStateException(s"Recovery hash index missing after replace: $hashName"))
      )
      ordinalBytes <- readBytes(ordinalName).flatMap(
        _.liftTo[F](new IllegalStateException(s"Recovery ordinal index missing after replace: $ordinalName"))
      )
      _ <- Async[F].raiseUnless(Arrays.equals(bytes, hashBytes) && Arrays.equals(bytes, ordinalBytes))(
        new IllegalStateException(s"Recovery snapshot exact disk readback failed ordinal=${snapshot.ordinal}")
      )
    } yield ()

  private def atomicReplace(fileName: String, bytes: Array[Byte]): F[Unit] = {
    val target = path.toNioPath.resolve(fileName)
    val parent = Path.fromNioPath(target.getParent)

    CrashSafeAtomicFileWriter.make[F](parent).flatMap(_.write(target.getFileName.toString, bytes))
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

  /** Enumerate every ordinal index above `ordinal`, including indexes after a missing/pruned chunk directory.
    *
    * Rollback anchors may be older than local retention while newer chunks are still present. Walking numerically until the first absent
    * chunk therefore mistakes a sparse directory tree for the end of history and leaves a future branch available after restart. Directory
    * names and ordinal file names are both treated as untrusted disk input; malformed entries are ignored, while every well-formed future
    * ordinal is returned.
    */
  def findAbove(ordinal: SnapshotOrdinal): Stream[F, File] =
    Stream.eval(streamOrdinalIndexesAbove(ordinal)).flatten

  private def streamIndexFiles(
    indexName: String,
    maxDepth: Int,
    include: File => Boolean
  ): F[Stream[F, File]] = {
    def entries(directory: File): Stream[F, File] =
      Stream
        .bracket(Async[F].blocking(JFiles.list(directory.path)))(listing => Async[F].blocking(listing.close()))
        .flatMap(listing => Stream.fromBlockingIterator[F](listing.iterator().asScala.map(File(_)), chunkSize = 64))
        .handleErrorWith {
          // A directory removed during cleanup has no remaining entries. Every
          // other listing/stat failure is safety-relevant and stays loud.
          case _: NoSuchFileException => Stream.empty
          case error                  => Stream.raiseError(error)
        }

    def loop(directory: File, depth: Int): Stream[F, File] =
      entries(directory).evalMap { child =>
        Async[F]
          .blocking(
            JFiles.readAttributes(child.path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
          )
          .map(attributes => child -> attributes)
          .attempt
      }.flatMap {
        case Left(_: NoSuchFileException) => Stream.empty
        case Left(error)                  => Stream.raiseError(error)
        case Right((child, attributes)) if attributes.isSymbolicLink =>
          Stream.raiseError(
            new IllegalStateException(s"Symbolic link is forbidden in $indexName snapshot index: ${child.pathAsString}")
          )
        case Right((child, attributes)) if attributes.isDirectory =>
          val childDepth = depth + 1
          if (childDepth < maxDepth) loop(child, childDepth)
          else
            Stream.raiseError(
              new IllegalStateException(
                s"Unexpected directory below $indexName snapshot index depth=$childDepth path=${child.pathAsString}"
              )
            )
        case Right((child, _)) if include(child) => Stream.emit(child)
        case Right(_)                            => Stream.empty
      }

    dir.map(_ / indexName).map { indexRoot =>
      Stream
        .eval(
          Async[F]
            .blocking(JFiles.readAttributes(indexRoot.path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS))
            .attempt
        )
        .flatMap {
          case Left(_: NoSuchFileException) => Stream.empty
          case Left(error)                  => Stream.raiseError(error)
          case Right(attributes) if !attributes.isDirectory || attributes.isSymbolicLink =>
            Stream.raiseError(
              new IllegalStateException(s"Snapshot index root is not a directory: ${indexRoot.pathAsString}")
            )
          case Right(_) => loop(indexRoot, depth = 0)
        }
        .handleErrorWith { error =>
          Stream.eval(logger.warn(error)(s"Error enumerating $indexName snapshot indexes")) >>
            Stream.raiseError(error)
        }
    }
  }

  private def streamOrdinalIndexesAbove(ordinal: SnapshotOrdinal): F[Stream[F, File]] =
    streamIndexFiles(
      indexName = "ordinal",
      maxDepth = 2,
      include = file => file.name.toLongOption.exists(_ > ordinal.value.value)
    )

  private def streamHashIndexes: F[Stream[F, File]] =
    streamIndexFiles(indexName = "hash", maxDepth = 3, include = _ => true)

  /** Return the POSIX hard-link count when the filesystem exposes it.
    *
    * A normal snapshot hash has an ordinal hardlink (`nlink >= 2`). A hash-only torn write or abandoned branch has `nlink == 1` and is the
    * only class whose body recovery needs to decode. On filesystems without the Unix attribute we conservatively return `None` and inspect
    * the body, preserving correctness at the cost of the old scan behavior.
    */
  private def hardLinkCount(file: File): F[Option[Long]] =
    Async[F].blocking {
      JFiles.getAttribute(file.path, "unix:nlink", LinkOption.NOFOLLOW_LINKS) match {
        case count: Number => count.longValue.some
        case _             => none[Long]
      }
    }.handleErrorWith {
      case _: NoSuchFileException           => 0L.some.pure[F]
      case _: UnsupportedOperationException => none[Long].pure[F]
      case _: IllegalArgumentException      => none[Long].pure[F]
      case error if Option(error.getCause).exists(_.isInstanceOf[UnsupportedOperationException]) =>
        none[Long].pure[F]
      case error => error.raiseError[F, Option[Long]]
    }

  /** Read one recovery candidate while keeping disk I/O failures distinct from invalid bytes.
    *
    * `SerializableLocalFileSystemStorage.read` intentionally hides ordinary JSON/Kryo decode failures as `None`, but a legacy fallback
    * decoder is allowed to throw. Recovery must quarantine invalid bytes while propagating EIO/EACCES/descriptor exhaustion unchanged;
    * otherwise a transient filesystem fault could destructively remove valid history and still report cleanup success.
    */
  private def readRecoveryIndex(fileName: String): F[Option[Signed[S]]] =
    readBytes(fileName).flatMap {
      case None => none[Signed[S]].pure[F]
      case Some(bytes) =>
        JsonSerializer[F].deserialize[Signed[S]](bytes).attempt.flatMap {
          case Right(Right(snapshot)) => snapshot.some.pure[F]
          case _ =>
            Async[F].delay(deserializeFallback(bytes)).attempt.map {
              case Right(Right(snapshot)) => snapshot.some
              case _                      => none[Signed[S]]
            }
        }
    }

  private def quarantineUnreadableHashIndex(file: File, reason: String): F[Unit] =
    dir
      .map(_ / ".recovery-quarantine" / "hash" / file.name)
      .flatMap { primaryDestination =>
        def move(destination: File): F[Unit] =
          logger.warn(
            s"Quarantining unreadable content-addressed snapshot path=${file.pathAsString} reason=$reason " +
              s"destination=${destination.pathAsString}"
          ) >> Async[F].blocking {
            destination.parent.createDirectoryIfNotExists(createParents = true)
            file.moveTo(destination)(File.CopyOptions(overwrite = false))
          }.void

        move(primaryDestination).handleErrorWith {
          case _: FileAlreadyExistsException =>
            val collisionSafeDestination =
              primaryDestination.parent / s"${primaryDestination.name}.${UUID.randomUUID().toString}"
            move(collisionSafeDestination)
          case error => error.raiseError[F, Unit]
        }
      }
      .handleErrorWith {
        case _: NoSuchFileException => Async[F].unit
        case error                  => error.raiseError[F, Unit]
      }

  private def unlinkRecoveryIndex(file: File): F[Unit] =
    Async[F]
      .blocking(file.delete())
      .void
      .handleErrorWith {
        case _: NoSuchFileException => Async[F].unit
        case error                  => error.raiseError[F, Unit]
      }

  private def isCanonicalOrdinalIndex(file: File, ordinal: SnapshotOrdinal): Boolean = {
    val expected = path.toNioPath.resolve(toOrdinalName(ordinal)).toAbsolutePath.normalize()
    val actual = file.path.toAbsolutePath.normalize()

    actual == expected
  }

  /** Remove a linked value occupying the recovery anchor before the orphan scan.
    *
    * The selected anchor may differ from the locally indexed value at the same ordinal. Both local indexes then have `nlink == 2`, so a
    * hash-only orphan filter would intentionally skip them. Resolve this one known conflict directly from the ordinal index first. An
    * unreadable anchor index is unlinked; its content hash consequently becomes an orphan and is quarantined by the subsequent scan.
    */
  private def cleanupConflictingAnchorOrdinal(
    ordinal: SnapshotOrdinal,
    retainedAnchorHash: Option[Hash],
    movePersistedToTmp: (Hash, SnapshotOrdinal) => F[Unit]
  )(implicit hs: HasherSelector[F]): F[Unit] =
    retainedAnchorHash.traverse_ { expectedHash =>
      val ordinalName = toOrdinalName(ordinal)

      readRecoveryIndex(ordinalName).flatMap {
        case Some(snapshot) if snapshot.ordinal === ordinal =>
          HasherSelector[F]
            .forOrdinal(ordinal) { implicit hasher =>
              snapshot.toHashed.map(_.hash)
            }
            .flatMap { actualHash =>
              movePersistedToTmp(actualHash, ordinal).unlessA(actualHash === expectedHash)
            }
        case Some(snapshot) =>
          logger.warn(
            s"Removing recovery-anchor ordinal index with mismatched body ordinal=${ordinal.show} " +
              s"bodyOrdinal=${snapshot.ordinal.show}"
          ) >> delete(ordinalName)
        case None =>
          exists(ordinalName).ifM(
            logger.warn(
              s"Removing unreadable recovery-anchor ordinal index ordinal=${ordinal.show}; " +
                "the retained hash is independently validated and the hash-tree pass will quarantine the abandoned bytes"
            ) >> delete(ordinalName),
            Async[F].unit
          )
      }
    }

  /** Remove content-addressed snapshots outside the selected recovery suffix.
    *
    * The hash tree is the only remaining source of truth after a torn ordinal write, so recovery scans it once without materializing it.
    * Canonical history has an ordinal hardlink and is skipped without decoding; only `nlink == 1` orphan candidates are inspected. An
    * unreadable orphan is removed from the remotely servable hash tree and preserved under `.recovery-quarantine/hash/` for diagnosis.
    */
  private def cleanupOrphanHashIndexesAboveOrdinal(
    ordinal: SnapshotOrdinal,
    retainedAnchorHash: Option[Hash],
    movePersistedToTmp: (Hash, SnapshotOrdinal) => F[Unit]
  )(implicit hs: HasherSelector[F]): F[Unit] =
    streamHashIndexes.flatMap(
      _.parEvalMapUnordered(maxParallelFileOperations) { file =>
        hardLinkCount(file).flatMap {
          case Some(count) if count > 1L || count === 0L => Async[F].unit
          case _ =>
            val hash = Hash(file.name)

            getPath(hash).flatMap { expectedFile =>
              val expectedPath = expectedFile.path.toAbsolutePath.normalize()
              val actualPath = file.path.toAbsolutePath.normalize()

              if (actualPath != expectedPath)
                quarantineUnreadableHashIndex(file, "misplaced_hash_index")
              else if (retainedAnchorHash.contains(hash))
                Async[F].unit
              else
                readRecoveryIndex(toHashName(hash)).flatMap {
                  case Some(snapshot) =>
                    HasherSelector[F]
                      .forOrdinal(snapshot.ordinal) { implicit hasher =>
                        snapshot.toHashed.map(_.hash)
                      }
                      .flatMap {
                        case actualHash if actualHash =!= hash =>
                          quarantineUnreadableHashIndex(file, s"content_hash_mismatch:${actualHash.value}")
                        case _
                            if snapshot.ordinal > ordinal ||
                              (snapshot.ordinal === ordinal && retainedAnchorHash.exists(_ =!= hash)) =>
                          movePersistedToTmp(hash, snapshot.ordinal).handleErrorWith {
                            case _: NoSuchFileException => Async[F].unit
                            case error                  => error.raiseError[F, Unit]
                          }
                        case _ => Async[F].unit
                      }
                  case None => quarantineUnreadableHashIndex(file, "deserialization_failed")
                }
            }
        }
      }.compile.drain
    )

  def processFileChunk(
    chunk: Stream[F, File],
    movePersistedToTmp: (Hash, SnapshotOrdinal) => F[Unit]
  )(implicit hs: HasherSelector[F]): F[Unit] =
    chunk
      .map(file => file -> file.name.toLongOption.flatMap(SnapshotOrdinal(_)))
      .collect { case (file, Some(fileOrdinal)) => file -> fileOrdinal }
      .parEvalMapUnordered(maxParallelFileOperations) { fileOrdinal =>
        val (file, ordinal) = fileOrdinal
        val operation =
          if (!isCanonicalOrdinalIndex(file, ordinal))
            logger.warn(
              s"Removing misplaced future ordinal index path=${file.pathAsString} ordinal=${ordinal.show}"
            ) >> unlinkRecoveryIndex(file)
          else
            readRecoveryIndex(toOrdinalName(ordinal)).flatMap {
              case Some(snapshot) if snapshot.ordinal === ordinal =>
                HasherSelector[F].forOrdinal(ordinal) { implicit hasher =>
                  for {
                    hashed <- snapshot.toHashed
                    _ <- movePersistedToTmp(hashed.hash, ordinal).handleErrorWith {
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
              case Some(snapshot) =>
                logger.warn(
                  s"Removing future ordinal index with mismatched body path=${file.pathAsString} " +
                    s"indexOrdinal=${ordinal.show} bodyOrdinal=${snapshot.ordinal.show}"
                ) >> unlinkRecoveryIndex(file)
              case None =>
                // The ordinal filename alone proves this index is in the discarded
                // suffix. Unlink it even if its body is torn; the following hash
                // scan then observes the remaining content inode as nlink=1 and
                // quarantines it instead of leaving it remotely servable.
                logger.warn(s"Removing unreadable future ordinal index path=${file.pathAsString} ordinal=${ordinal.show}") >>
                  unlinkRecoveryIndex(file)
            }

        operation.handleErrorWith {
          case _: java.nio.file.NoSuchFileException =>
            // File was deleted between listing and processing - expected during cleanup.
            Async[F].unit
          case err =>
            // Recovery callers use this method to prove that no future branch
            // remains servable. Logging-and-continuing would let verification
            // miss an ordinal removed just before its hash cleanup failed.
            logger.warn(err)(s"Failed to process file with ordinal $ordinal") >>
              err.raiseError[F, Unit]
        }
      }
      .compile
      .drain

  def cleanupAboveOrdinal(
    ordinal: SnapshotOrdinal,
    movePersistedToTmp: (Hash, SnapshotOrdinal) => F[Unit]
  )(implicit hs: HasherSelector[F]): F[Unit] =
    cleanupCanonicalSuffix(ordinal, none, movePersistedToTmp)

  /** Remove every persisted successor and every alternate value at the anchor ordinal, retaining only `anchorHash`.
    *
    * Recovery can encounter multiple content-addressed values for the same ordinal after a fork or a crash between the hash and ordinal
    * index replacements. Replacing the ordinal link alone is insufficient because peers can still request an abandoned value directly by
    * hash. The full hash-tree scan therefore treats all same-ordinal values except the selected anchor as part of the discarded suffix.
    */
  def cleanupCanonicalSuffix(
    ordinal: SnapshotOrdinal,
    anchorHash: Hash,
    movePersistedToTmp: (Hash, SnapshotOrdinal) => F[Unit]
  )(implicit hs: HasherSelector[F]): F[Unit] =
    cleanupCanonicalSuffix(ordinal, anchorHash.some, movePersistedToTmp)

  private def cleanupCanonicalSuffix(
    ordinal: SnapshotOrdinal,
    retainedAnchorHash: Option[Hash],
    movePersistedToTmp: (Hash, SnapshotOrdinal) => F[Unit]
  )(implicit hs: HasherSelector[F]): F[Unit] =
    for {
      _ <- logger.debug(s"Searching for persisted files above ordinal ${ordinal.show}")
      ordinalIndexes <- streamOrdinalIndexesAbove(ordinal)
      _ <- processFileChunk(ordinalIndexes, movePersistedToTmp)
      _ <- cleanupConflictingAnchorOrdinal(ordinal, retainedAnchorHash, movePersistedToTmp)
      // Re-scan the hash tree after normal ordinal cleanup. Remaining future
      // entries are precisely torn/orphaned content indexes and must not stay
      // remotely servable after the recovered head is installed.
      _ <- cleanupOrphanHashIndexesAboveOrdinal(ordinal, retainedAnchorHash, movePersistedToTmp)
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
