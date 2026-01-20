package io.constellationnetwork.schema.mpt

import cats.Parallel
import cats.effect.{Async, Ref}
import cats.syntax.all._

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt._
import io.constellationnetwork.security.mpt.producer._

import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait MptStore[F[_], K] {
  def get[V: Decoder](key: K): F[Option[V]]
  def getMany[V: Decoder](keys: List[K]): F[Map[K, V]]
  def insert[V: Encoder](key: K, value: V): F[Unit]
  def insert[V: Encoder](entries: Map[K, V]): F[Unit]
  def remove(key: K): F[Unit]
  def remove(keys: List[K]): F[Unit]
  def contains(key: K): F[Boolean]
  def clear: F[Unit]
  def build: F[Either[MerklePatriciaError, MerklePatriciaTrie]]

  def buildWithRootHash: F[Either[MerklePatriciaError, (MerklePatriciaTrie, MptRoot)]]

  def sync[V: Encoder](newState: Map[K, V], ordinal: SnapshotOrdinal): F[Unit]
  def syncFull[V: Encoder](newState: Map[K, V], ordinal: SnapshotOrdinal): F[Unit]
  def update[V: Encoder](toUpsert: Map[K, V], toRemove: Set[K]): F[Unit]
  def underlying: StatefulMerklePatriciaProducer[F]
  def deleteAbove(ordinal: SnapshotOrdinal): F[Unit]
}

object MptStore {

  def make[F[_]: Async: Parallel: Hasher: JsonSerializer, K](
    producer: StatefulMerklePatriciaProducer[F],
    toHex: K => F[Hex]
  ): F[MptStore[F, K]] =
    for {
      trieRef <- Ref.of[F, Option[MerklePatriciaTrie]](None)
    } yield new Impl[F, K](producer, toHex, trieRef)

  private final class Impl[F[_]: Async: Parallel: Hasher: JsonSerializer, K](
    producer: StatefulMerklePatriciaProducer[F],
    toHex: K => F[Hex],
    trieRef: Ref[F, Option[MerklePatriciaTrie]]
  ) extends MptStore[F, K] {

    private val logger = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)
    private val BatchSize = 5000

    private def persistAndCutoffAsync(ordinal: SnapshotOrdinal): F[Unit] =
      producer match {
        case p: StatefulWithPersistenceMerklePatriciaProducer[F @unchecked] =>
          Async[F].start {
            p.persist(ordinal) >> p.applyCutoff(ordinal)
          }.void
        case _ =>
          ().pure
      }

    /** Serialize value directly to bytes without intermediate Json AST */
    private def serializeToBytes[V: Encoder](v: V): F[Array[Byte]] =
      Async[F].delay {
        Encoder[V].apply(v).noSpaces.getBytes("UTF-8")
      }

    /** Convert entries to hex -> bytes, avoiding Json AST retention */
    private def toHexEntries[V: Encoder](data: Map[K, V]): F[Map[Hex, Array[Byte]]] =
      if (data.isEmpty) Map.empty[Hex, Array[Byte]].pure[F]
      else if (data.size <= BatchSize) {
        data.toList.parTraverse {
          case (k, v) =>
            for {
              hex <- toHex(k)
              bytes <- serializeToBytes(v)
            } yield hex -> bytes
        }.map(_.toMap)
      } else {
        data.toList
          .grouped(BatchSize)
          .toList
          .foldLeftM(Map.empty[Hex, Array[Byte]]) { (acc, batch) =>
            for {
              batchResult <- batch.parTraverse {
                case (k, v) =>
                  for {
                    hex <- toHex(k)
                    bytes <- serializeToBytes(v)
                  } yield hex -> bytes
              }
              _ <- Async[F].cede
            } yield acc ++ batchResult.toMap
          }
      }

    /** Convert entries to (hex, hash, bytes) tuples for trie insertion */
    private def toHexHashes[V: Encoder](data: Map[K, V]): F[List[(Hex, Hash, Array[Byte])]] =
      if (data.isEmpty) List.empty[(Hex, Hash, Array[Byte])].pure[F]
      else if (data.size <= BatchSize) {
        data.toList.parTraverse {
          case (k, v) =>
            for {
              hex <- toHex(k)
              bytes <- serializeToBytes(v)
              hash <- Hasher[F].hashBytes(bytes)
            } yield (hex, hash, bytes)
        }
      } else {
        data.toList
          .grouped(BatchSize)
          .toList
          .flatTraverse { batch =>
            batch.parTraverse {
              case (k, v) =>
                for {
                  hex <- toHex(k)
                  bytes <- serializeToBytes(v)
                  hash <- Hasher[F].hashBytes(bytes)
                } yield (hex, hash, bytes)
            } <* Async[F].cede
          }
      }

    private def deserializeBytes[V: Decoder](bytes: Array[Byte]): F[Option[V]] =
      JsonSerializer[F].deserialize[Json](bytes).flatMap {
        case Right(json) =>
          json.as[V] match {
            case Right(v) => v.some.pure[F]
            case Left(_)  => none[V].pure[F]
          }
        case Left(_) => none[V].pure[F]
      }

    private def insertIntoTrie(hex: Hex, dataHash: Hash): F[Unit] =
      trieRef.get.flatMap {
        case Some(trie) =>
          IncrementalTrieOps.insert[F](trie.rootNode, hex, dataHash).flatMap { newRoot =>
            trieRef.set(Some(MerklePatriciaTrie(newRoot)))
          }
        case None =>
          ().pure[F]
      }

    private def removeFromTrie(hex: Hex): F[Unit] =
      trieRef.get.flatMap {
        case Some(trie) =>
          IncrementalTrieOps.remove[F](trie.rootNode, hex).flatMap { newRoot =>
            trieRef.set(Some(MerklePatriciaTrie(newRoot)))
          }
        case None =>
          ().pure[F]
      }

    override def get[V: Decoder](key: K): F[Option[V]] =
      for {
        hex <- toHex(key)
        entries <- producer.entries
        result <- entries.get(hex).flatTraverse(deserializeBytes[V])
      } yield result

    override def getMany[V: Decoder](keys: List[K]): F[Map[K, V]] =
      if (keys.isEmpty) Map.empty[K, V].pure[F]
      else
        for {
          hexKeys <- keys.parTraverse(k => toHex(k).map(k -> _))
          entries <- producer.entries
          results <- hexKeys.traverseFilter {
            case (k, hex) =>
              entries.get(hex).flatTraverse { bytes =>
                deserializeBytes[V](bytes).map(_.map(k -> _))
              }
          }
        } yield results.toMap

    override def insert[V: Encoder](key: K, value: V): F[Unit] =
      for {
        hex <- toHex(key)
        bytes <- serializeToBytes(value)
        dataHash <- Hasher[F].hashBytes(bytes)
        _ <- producer.insertBytes(Map(hex -> bytes)).void
        _ <- insertIntoTrie(hex, dataHash)
      } yield ()

    override def insert[V: Encoder](data: Map[K, V]): F[Unit] =
      if (data.isEmpty) Async[F].unit
      else
        for {
          start <- Async[F].realTime
          entries <- toHexHashes(data)

          _ <- producer.insertBytes(entries.map { case (hex, _, bytes) => hex -> bytes }.toMap).void

          _ <- trieRef.get.flatMap {
            case Some(trie) =>
              entries
                .foldLeftM(trie.rootNode) {
                  case (root, (hex, hash, _)) =>
                    IncrementalTrieOps.insert[F](root, hex, hash)
                }
                .flatMap { newRoot =>
                  trieRef.set(Some(MerklePatriciaTrie(newRoot)))
                }
            case None =>
              ().pure[F]
          }

          end <- Async[F].realTime
          _ <- logger.debug(s"Incremental insert of ${data.size} entries took ${(end - start).toMillis}ms")
        } yield ()

    override def remove(key: K): F[Unit] =
      for {
        hex <- toHex(key)
        _ <- producer.remove(List(hex)).void
        _ <- removeFromTrie(hex)
      } yield ()

    override def remove(keys: List[K]): F[Unit] =
      if (keys.isEmpty) Async[F].unit
      else
        for {
          hexKeys <- keys.parTraverse(toHex)
          _ <- producer.remove(hexKeys).void
          _ <- trieRef.get.flatMap {
            case Some(trie) =>
              hexKeys
                .foldLeftM(trie.rootNode) {
                  case (root, hex) =>
                    IncrementalTrieOps.remove[F](root, hex)
                }
                .flatMap { newRoot =>
                  trieRef.set(Some(MerklePatriciaTrie(newRoot)))
                }
            case None =>
              ().pure[F]
          }
        } yield ()

    override def contains(key: K): F[Boolean] =
      for {
        hex <- toHex(key)
        entries <- producer.entries
      } yield entries.contains(hex)

    override def clear: F[Unit] =
      for {
        _ <- logger.info("Clearing MPT store")
        _ <- trieRef.set(None)
        _ <- producer.clear
      } yield ()

    override def build: F[Either[MerklePatriciaError, MerklePatriciaTrie]] =
      trieRef.get.flatMap {
        case Some(trie) =>
          trie.asRight[MerklePatriciaError].pure[F]
        case None =>
          producer.build.flatTap {
            case Right(trie) => trieRef.set(Some(trie))
            case Left(_)     => ().pure[F]
          }
      }

    override def buildWithRootHash: F[Either[MerklePatriciaError, (MerklePatriciaTrie, MptRoot)]] =
      for {
        buildResult <- build
        result <- buildResult match {
          case Right(trie) =>
            for {
              start <- Async[F].realTime
              (rootHash, updatedTrie) <- MerklePatriciaTrie.rootHash[F](trie)
              _ <- trieRef.set(Some(updatedTrie))
              end <- Async[F].realTime
              _ <- logger.info(s"Incremental root hash computation took ${(end - start).toMillis}ms")
            } yield (updatedTrie, rootHash).asRight[MerklePatriciaError]
          case Left(err) =>
            err.asLeft[(MerklePatriciaTrie, MptRoot)].pure[F]
        }
      } yield result

    override def syncFull[V: Encoder](newState: Map[K, V], ordinal: SnapshotOrdinal): F[Unit] =
      if (newState.isEmpty) {
        clear
      } else {
        for {
          _ <- logger.info(s"Performing full sync with ${newState.size} entries...")
          _ <- clear
          newEntries <- toHexEntries(newState)
          _ <- producer.insertBytes(newEntries).void
          _ <- producer.build.flatMap {
            case Right(trie) => trieRef.set(Some(trie))
            case Left(_)     => ().pure[F]
          }
          _ <- persistAndCutoffAsync(ordinal)
        } yield ()
      }

    override def sync[V: Encoder](updates: Map[K, V], ordinal: SnapshotOrdinal): F[Unit] =
      if (updates.isEmpty) {
        ().pure
      } else {
        for {
          _ <- insert(updates)
          _ <- persistAndCutoffAsync(ordinal)
        } yield ()
      }

    override def update[V: Encoder](toUpsert: Map[K, V], toRemove: Set[K]): F[Unit] =
      for {
        _ <- remove(toRemove.toList)
        _ <- insert(toUpsert)
      } yield ()

    override def underlying: StatefulMerklePatriciaProducer[F] = producer

    override def deleteAbove(ordinal: SnapshotOrdinal): F[Unit] =
      producer match {
        case p: StatefulWithPersistenceMerklePatriciaProducer[F @unchecked] =>
          logger.info(s"Deleting above ordinal=$ordinal") >>
            p.deleteAbove(ordinal)
        case _ =>
          Async[F].unit
      }
  }

  /** Incremental trie operations that mark only affected paths as dirty */
  private object IncrementalTrieOps {

    def insert[F[_]: Async: Hasher](
      root: MerklePatriciaNode,
      key: Hex,
      dataDigest: Hash
    ): F[MerklePatriciaNode] = {
      val nibbles = CompactNibblePath.fromHexString(key.value)
      insertAt[F](root, nibbles, 0, dataDigest)
    }

    def remove[F[_]: Async: Hasher](
      root: MerklePatriciaNode,
      key: Hex
    ): F[MerklePatriciaNode] = {
      val nibbles = CompactNibblePath.fromHexString(key.value)
      removeAt[F](root, nibbles, 0)
    }

    private def insertAt[F[_]: Async: Hasher](
      node: MerklePatriciaNode,
      key: CompactNibblePath,
      depth: Int,
      dataDigest: Hash
    ): F[MerklePatriciaNode] =
      node match {
        case branch: MerklePatriciaNode.Branch =>
          insertIntoBranch[F](branch, key, depth, dataDigest)
        case ext: MerklePatriciaNode.Extension =>
          insertIntoExtension[F](ext, key, depth, dataDigest)
        case leaf: MerklePatriciaNode.Leaf =>
          insertIntoLeaf[F](leaf, key, depth, dataDigest)
      }

    private def insertIntoBranch[F[_]: Async: Hasher](
      branch: MerklePatriciaNode.Branch,
      key: CompactNibblePath,
      depth: Int,
      dataDigest: Hash
    ): F[MerklePatriciaNode] =
      if (depth >= key.length) {
        branch.markDirty.pure[F].widen
      } else {
        val nibble = key.get(depth)
        branch.getChild(nibble) match {
          case Some(child) =>
            insertAt[F](child, key, depth + 1, dataDigest).map { updatedChild =>
              branch.withUpdatedChild(nibble, updatedChild)
            }
          case None =>
            val remaining = if (depth + 1 >= key.length) CompactNibblePath.empty else key.drop(depth + 1)
            MerklePatriciaNode.Leaf.fromDataDigest[F](remaining, dataDigest).map { newLeaf =>
              branch.withUpdatedChild(nibble, newLeaf)
            }
        }
      }

    private def insertIntoExtension[F[_]: Async: Hasher](
      ext: MerklePatriciaNode.Extension,
      key: CompactNibblePath,
      depth: Int,
      dataDigest: Hash
    ): F[MerklePatriciaNode] = {
      val shared = ext.sharedPath
      val keyRemaining = if (depth >= key.length) CompactNibblePath.empty else key.drop(depth)
      val commonLen = shared.commonPrefixLength(keyRemaining)

      if (commonLen == shared.length) {
        insertAt[F](ext.child, key, depth + shared.length, dataDigest).map { updatedChild =>
          ext.withUpdatedChild(updatedChild.asInstanceOf[MerklePatriciaNode.Branch])
        }
      } else {
        splitExtension[F](ext, keyRemaining, dataDigest, commonLen)
      }
    }

    private def splitExtension[F[_]: Async: Hasher](
      ext: MerklePatriciaNode.Extension,
      keyRemaining: CompactNibblePath,
      dataDigest: Hash,
      commonLen: Int
    ): F[MerklePatriciaNode] = {
      val shared = ext.sharedPath

      for {
        newLeafRemaining <- Async[F].pure(
          if (commonLen + 1 >= keyRemaining.length) CompactNibblePath.empty
          else keyRemaining.drop(commonLen + 1)
        )
        newLeaf <- MerklePatriciaNode.Leaf.fromDataDigest[F](newLeafRemaining, dataDigest)

        existingNibble = shared.get(commonLen)
        newNibble = keyRemaining.get(commonLen)

        newBranch <- {
          val existingChildPath = if (commonLen + 1 >= shared.length) CompactNibblePath.empty else shared.drop(commonLen + 1)
          if (existingChildPath.isEmpty) {
            MerklePatriciaNode.Branch.fromByteKeys[F](
              Map(
                existingNibble -> ext.child,
                newNibble -> newLeaf
              )
            )
          } else {
            MerklePatriciaNode.Extension.fromCompact[F](existingChildPath, ext.child).flatMap { newExt =>
              MerklePatriciaNode.Branch.fromByteKeys[F](
                Map(
                  existingNibble -> newExt,
                  newNibble -> newLeaf
                )
              )
            }
          }
        }

        result <-
          if (commonLen > 0) {
            val commonPath = shared.take(commonLen)
            MerklePatriciaNode.Extension.fromCompact[F](commonPath, newBranch).widen
          } else {
            newBranch.pure[F].widen
          }
      } yield result
    }

    private def insertIntoLeaf[F[_]: Async: Hasher](
      leaf: MerklePatriciaNode.Leaf,
      key: CompactNibblePath,
      depth: Int,
      dataDigest: Hash
    ): F[MerklePatriciaNode] = {
      val leafRemaining = leaf.remainingPath
      val keyRemaining = if (depth >= key.length) CompactNibblePath.empty else key.drop(depth)

      if (leafRemaining == keyRemaining) {
        MerklePatriciaNode.Leaf.fromDataDigest[F](leafRemaining, dataDigest).widen
      } else {
        val commonLen = leafRemaining.commonPrefixLength(keyRemaining)

        for {
          newLeafRemaining <- Async[F].pure(
            if (commonLen + 1 >= keyRemaining.length) CompactNibblePath.empty
            else keyRemaining.drop(commonLen + 1)
          )
          newLeaf <- MerklePatriciaNode.Leaf.fromDataDigest[F](newLeafRemaining, dataDigest)

          existingLeafRemaining = if (commonLen + 1 >= leafRemaining.length) CompactNibblePath.empty else leafRemaining.drop(commonLen + 1)
          existingLeaf <- MerklePatriciaNode.Leaf.fromDataDigest[F](existingLeafRemaining, leaf.dataDigest)

          existingNibble = if (commonLen < leafRemaining.length) leafRemaining.get(commonLen) else 0.toByte
          newNibble = if (commonLen < keyRemaining.length) keyRemaining.get(commonLen) else 0.toByte

          branch <- MerklePatriciaNode.Branch.fromByteKeys[F](
            Map(
              existingNibble -> existingLeaf,
              newNibble -> newLeaf
            )
          )

          result <-
            if (commonLen > 0) {
              val commonPath = leafRemaining.take(commonLen)
              MerklePatriciaNode.Extension.fromCompact[F](commonPath, branch).widen
            } else {
              branch.pure[F].widen
            }
        } yield result
      }
    }

    private def removeAt[F[_]: Async: Hasher](
      node: MerklePatriciaNode,
      key: CompactNibblePath,
      depth: Int
    ): F[MerklePatriciaNode] =
      node match {
        case branch: MerklePatriciaNode.Branch =>
          removeFromBranch[F](branch, key, depth)
        case ext: MerklePatriciaNode.Extension =>
          removeFromExtension[F](ext, key, depth)
        case leaf: MerklePatriciaNode.Leaf =>
          val leafKey = leaf.remainingPath
          val keyRemaining = if (depth >= key.length) CompactNibblePath.empty else key.drop(depth)
          if (leafKey == keyRemaining) {
            MerklePatriciaNode.Branch.empty[F].widen
          } else {
            leaf.pure[F].widen
          }
      }

    private def removeFromBranch[F[_]: Async: Hasher](
      branch: MerklePatriciaNode.Branch,
      key: CompactNibblePath,
      depth: Int
    ): F[MerklePatriciaNode] =
      if (depth >= key.length) {
        branch.pure[F].widen
      } else {
        val nibble = key.get(depth)
        branch.getChild(nibble) match {
          case Some(child) =>
            removeAt[F](child, key, depth + 1).flatMap { updatedChild =>
              updatedChild match {
                case b: MerklePatriciaNode.Branch if b.childCount == 0 =>
                  val newBranch = branch.withRemovedChild(nibble)
                  collapseIfNeeded[F](newBranch)
                case _ =>
                  collapseIfNeeded[F](branch.withUpdatedChild(nibble, updatedChild))
              }
            }
          case None =>
            branch.pure[F].widen
        }
      }

    private def removeFromExtension[F[_]: Async: Hasher](
      ext: MerklePatriciaNode.Extension,
      key: CompactNibblePath,
      depth: Int
    ): F[MerklePatriciaNode] = {
      val shared = ext.sharedPath
      val keyRemaining = if (depth >= key.length) CompactNibblePath.empty else key.drop(depth)

      if (keyRemaining.startsWith(shared)) {
        removeAt[F](ext.child, key, depth + shared.length).flatMap { updatedChild =>
          updatedChild match {
            case b: MerklePatriciaNode.Branch if b.childCount == 0 =>
              MerklePatriciaNode.Branch.empty[F].widen
            case b: MerklePatriciaNode.Branch if b.childCount == 1 =>
              collapseExtensionWithBranch[F](shared, b)
            case b: MerklePatriciaNode.Branch =>
              ext.withUpdatedChild(b).pure[F].widen
            case _ =>
              ext.pure[F].widen
          }
        }
      } else {
        ext.pure[F].widen
      }
    }

    private def collapseIfNeeded[F[_]: Async: Hasher](branch: MerklePatriciaNode.Branch): F[MerklePatriciaNode] =
      if (branch.childCount == 1) {
        val (nibble, child) = branch.internalPaths.head
        child match {
          case leaf: MerklePatriciaNode.Leaf =>
            MerklePatriciaNode.Leaf
              .fromDataDigest[F](
                CompactNibblePath.single(nibble) ++ leaf.remainingPath,
                leaf.dataDigest
              )
              .widen
          case ext: MerklePatriciaNode.Extension =>
            MerklePatriciaNode.Extension
              .fromCompact[F](
                CompactNibblePath.single(nibble) ++ ext.sharedPath,
                ext.child
              )
              .widen
          case b: MerklePatriciaNode.Branch =>
            MerklePatriciaNode.Extension
              .fromCompact[F](
                CompactNibblePath.single(nibble),
                b
              )
              .widen
        }
      } else {
        branch.pure[F].widen
      }

    private def collapseExtensionWithBranch[F[_]: Async: Hasher](
      extPath: CompactNibblePath,
      branch: MerklePatriciaNode.Branch
    ): F[MerklePatriciaNode] = {
      val (nibble, child) = branch.internalPaths.head
      val newPath = extPath ++ CompactNibblePath.single(nibble)

      child match {
        case leaf: MerklePatriciaNode.Leaf =>
          MerklePatriciaNode.Leaf.fromDataDigest[F](newPath ++ leaf.remainingPath, leaf.dataDigest).widen
        case ext: MerklePatriciaNode.Extension =>
          MerklePatriciaNode.Extension.fromCompact[F](newPath ++ ext.sharedPath, ext.child).widen
        case b: MerklePatriciaNode.Branch =>
          MerklePatriciaNode.Extension.fromCompact[F](newPath, b).widen
      }
    }
  }
}
