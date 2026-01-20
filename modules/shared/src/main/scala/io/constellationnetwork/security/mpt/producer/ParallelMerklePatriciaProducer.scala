package io.constellationnetwork.security.mpt.producer

import java.util.concurrent.{CountDownLatch, Executors}

import cats.Parallel
import cats.effect.Async
import cats.syntax.all._

import scala.collection.mutable

import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt._
import io.constellationnetwork.security.mpt.prover.MerklePatriciaSingleInclusionProver

import io.circe.syntax._
import io.circe.{Encoder, Json}
import org.typelevel.log4cats.slf4j.Slf4jLogger

case class ParallelTrieWithData(trie: MerklePatriciaTrie)

class ParallelMerklePatriciaProducer[F[_]: Hasher: Async: Parallel](
  maxThreads: Int = Runtime.getRuntime.availableProcessors()
) extends MerklePatriciaProducer[F] {

  private val logger = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

  private val ParallelDepthThreshold = 10

  def getProver(trie: MerklePatriciaTrie): F[MerklePatriciaSingleInclusionProver[F]] =
    MerklePatriciaSingleInclusionProver.make[F](trie).pure[F]

  // ==================== Bytes-based API (Memory Efficient) ====================

  /** Create trie from pre-serialized bytes - MEMORY EFFICIENT */
  def createFromBytes(data: Map[Hex, Array[Byte]]): F[MerklePatriciaTrie] =
    if (data.isEmpty) {
      MerklePatriciaNode.Branch.empty[F].map(MerklePatriciaTrie(_))
    } else {
      for {
        totalStart <- Async[F].realTime
        _ <- logger.info(s"Starting MPT creation with ${data.size} entries (bytes)")

        prepStart <- Async[F].realTime
        entries <- Async[F].blocking(prepareAndSortBytes(data))
        prepEnd <- Async[F].realTime
        _ <- logger.info(s"Phase 1 - prepareAndSortBytes took ${(prepEnd - prepStart).toMillis}ms")

        hashStart <- Async[F].realTime
        dataHashes <- batchComputeDataHashesFromBytes(entries)
        hashEnd <- Async[F].realTime
        _ <- logger.info(s"Phase 2 - batchComputeDataHashesFromBytes took ${(hashEnd - hashStart).toMillis}ms")

        buildStart <- Async[F].realTime
        root <- buildTreeFromBytes(entries, dataHashes, 0, entries.length, 0)
        buildEnd <- Async[F].realTime
        _ <- logger.info(s"Phase 3 - buildTreeFromBytes took ${(buildEnd - buildStart).toMillis}ms")

        totalEnd <- Async[F].realTime
        _ <- logger.info(s"Total MPT creation (bytes) took ${(totalEnd - totalStart).toMillis}ms")
      } yield MerklePatriciaTrie(root)
    }

  /** Incremental insert from pre-serialized bytes - MEMORY EFFICIENT */
  def insertFromBytes(
    current: MerklePatriciaTrie,
    data: Map[Hex, Array[Byte]]
  ): F[Either[MerklePatriciaError, MerklePatriciaTrie]] =
    if (data.isEmpty) {
      current.asRight[MerklePatriciaError].pure[F]
    } else {
      for {
        start <- Async[F].realTime
        _ <- logger.debug(s"Incremental insert (bytes): ${data.size} entries")

        hashesWithKeys <- data.toList.parTraverse {
          case (key, bytes) =>
            Hasher[F].hashBytes(bytes).map(hash => (key, hash))
        }

        result <- hashesWithKeys.foldLeftM(current.rootNode) {
          case (root, (key, dataHash)) =>
            insertAt(root, CompactNibblePath.fromHexString(key.value), 0, dataHash)
        }

        end <- Async[F].realTime
        _ <- logger.debug(s"Incremental insert (bytes) completed in ${(end - start).toMillis}ms")
      } yield MerklePatriciaTrie(result).asRight[MerklePatriciaError]
    }

  private def prepareAndSortBytes(data: Map[Hex, Array[Byte]]): Array[(CompactNibblePath, Array[Byte])] = {
    val dataArray = data.toArray
    val results = new Array[(CompactNibblePath, Array[Byte])](dataArray.length)
    val numThreads = maxThreads

    val chunkSize = math.max(1, (dataArray.length + numThreads - 1) / numThreads)
    val executor = Executors.newFixedThreadPool(numThreads)
    val latch = new CountDownLatch(numThreads)

    for (i <- 0 until numThreads) {
      val startIdx = i * chunkSize
      val endIdx = math.min(startIdx + chunkSize, dataArray.length)
      executor.submit(new Runnable {
        def run(): Unit =
          try {
            var j = startIdx
            while (j < endIdx) {
              val (hex, bytes) = dataArray(j)
              results(j) = (CompactNibblePath.fromHexString(hex.value), bytes)
              j += 1
            }
          } finally
            latch.countDown()
      })
    }
    latch.await()
    executor.shutdown()

    java.util.Arrays.parallelSort(
      results,
      (a: (CompactNibblePath, Array[Byte]), b: (CompactNibblePath, Array[Byte])) => CompactNibblePath.ordering.compare(a._1, b._1)
    )

    results
  }

  private def batchComputeDataHashesFromBytes(entries: Array[(CompactNibblePath, Array[Byte])]): F[Array[String]] = {
    val numEntries = entries.length
    val batchSize = math.max(100, numEntries / (maxThreads * 4))

    for {
      results <- Async[F].delay(new Array[String](numEntries))
      batches = entries.indices.toList.grouped(batchSize).toList
      _ <- batches.parTraverse_ { batch =>
        batch.traverse_ { i =>
          Hasher[F].hashBytes(entries(i)._2).flatMap { hash =>
            Async[F].delay(results(i) = hash.value)
          }
        }
      }
    } yield results
  }

  private def buildTreeFromBytes(
    entries: Array[(CompactNibblePath, Array[Byte])],
    dataHashes: Array[String],
    start: Int,
    end: Int,
    depth: Int
  ): F[MerklePatriciaNode] = {
    val size = end - start

    if (size == 0) {
      MerklePatriciaNode.Branch.empty[F].widen
    } else if (size == 1) {
      val (path, _) = entries(start)
      val dataHash = Hash(dataHashes(start))
      val remaining = if (depth >= path.length) CompactNibblePath.empty else path.drop(depth)
      MerklePatriciaNode.Leaf.fromDataDigest[F](remaining, dataHash).widen
    } else {
      buildWithGroupsFromBytes(entries, dataHashes, start, end, depth)
    }
  }

  private def buildWithGroupsFromBytes(
    entries: Array[(CompactNibblePath, Array[Byte])],
    dataHashes: Array[String],
    start: Int,
    end: Int,
    depth: Int
  ): F[MerklePatriciaNode] = {
    val groups = findGroupsBytes(entries, start, end, depth)

    if (groups.length == 1) {
      val (nibbleValue, gs, ge) = groups(0)
      buildTreeFromBytes(entries, dataHashes, gs, ge, depth + 1)
        .flatMap(child => createSingleGroupNode(nibbleValue, child))
    } else {
      val useParallel = depth <= ParallelDepthThreshold || groups.length >= 4

      val buildChildren: F[List[(Byte, MerklePatriciaNode)]] =
        if (useParallel) {
          groups.toList.parTraverse {
            case (nibbleValue, gs, ge) =>
              buildTreeFromBytes(entries, dataHashes, gs, ge, depth + 1).map(nibbleValue -> _)
          }
        } else {
          groups.toList.traverse {
            case (nibbleValue, gs, ge) =>
              buildTreeFromBytes(entries, dataHashes, gs, ge, depth + 1).map(nibbleValue -> _)
          }
        }

      buildChildren.flatMap { children =>
        MerklePatriciaNode.Branch.fromByteKeys[F](children.toMap).widen
      }
    }
  }

  @inline private def findGroupsBytes(
    entries: Array[(CompactNibblePath, Array[Byte])],
    start: Int,
    end: Int,
    depth: Int
  ): mutable.ArrayBuffer[(Byte, Int, Int)] = {
    val groups = mutable.ArrayBuffer[(Byte, Int, Int)]()
    var groupStart = start
    var currentNibble = entries(start)._1.getOrEmpty(depth)

    var i = start + 1
    while (i < end) {
      val nibble = entries(i)._1.getOrEmpty(depth)
      if (nibble != currentNibble) {
        groups += ((currentNibble, groupStart, i))
        groupStart = i
        currentNibble = nibble
      }
      i += 1
    }
    groups += ((currentNibble, groupStart, end))
    groups
  }

  // ==================== Legacy Json-based API (for backward compatibility) ====================

  /** Create trie from Json - LEGACY, use createFromBytes for better memory efficiency */
  def create[A: Encoder](data: Map[Hex, A]): F[MerklePatriciaTrie] =
    if (data.isEmpty) {
      MerklePatriciaNode.Branch.empty[F].map(MerklePatriciaTrie(_))
    } else {
      for {
        totalStart <- Async[F].realTime
        _ <- logger.info(s"Starting MPT creation with ${data.size} entries")

        prepStart <- Async[F].realTime
        entries <- Async[F].blocking(prepareAndSort(data))
        prepEnd <- Async[F].realTime
        _ <- logger.info(s"Phase 1 - prepareAndSort took ${(prepEnd - prepStart).toMillis}ms")

        hashStart <- Async[F].realTime
        dataHashes <- batchComputeDataHashes(entries)
        hashEnd <- Async[F].realTime
        _ <- logger.info(s"Phase 2 - batchComputeDataHashes took ${(hashEnd - hashStart).toMillis}ms")

        buildStart <- Async[F].realTime
        root <- buildTreeWithHashes(entries, dataHashes, 0, entries.length, 0)
        buildEnd <- Async[F].realTime
        _ <- logger.info(s"Phase 3 - buildTree took ${(buildEnd - buildStart).toMillis}ms")

        totalEnd <- Async[F].realTime
        _ <- logger.info(s"Total MPT creation took ${(totalEnd - totalStart).toMillis}ms")
      } yield MerklePatriciaTrie(root)
    }

  /** Incremental insert - LEGACY, use insertFromBytes for better memory efficiency */
  def insert[A: Encoder](
    current: MerklePatriciaTrie,
    data: Map[Hex, A]
  ): F[Either[MerklePatriciaError, MerklePatriciaTrie]] =
    if (data.isEmpty) {
      current.asRight[MerklePatriciaError].pure[F]
    } else {
      for {
        start <- Async[F].realTime
        _ <- logger.debug(s"Incremental insert: ${data.size} entries")

        hashesWithKeys <- data.toList.parTraverse {
          case (key, value) =>
            Hasher[F].hash(value.asJson).map(hash => (key, hash))
        }

        result <- hashesWithKeys.foldLeftM(current.rootNode) {
          case (root, (key, dataHash)) =>
            insertAt(root, CompactNibblePath.fromHexString(key.value), 0, dataHash)
        }

        end <- Async[F].realTime
        _ <- logger.debug(s"Incremental insert completed in ${(end - start).toMillis}ms")
      } yield MerklePatriciaTrie(result).asRight[MerklePatriciaError]
    }

  /** Incremental remove - marks only affected paths as dirty. */
  def remove(
    current: MerklePatriciaTrie,
    data: List[Hex]
  ): F[Either[MerklePatriciaError, MerklePatriciaTrie]] =
    if (data.isEmpty) {
      current.asRight[MerklePatriciaError].pure[F]
    } else {
      for {
        start <- Async[F].realTime
        _ <- logger.debug(s"Incremental remove: ${data.size} entries")

        result <- data.foldLeftM(current.rootNode) {
          case (root, key) =>
            removeAt(root, CompactNibblePath.fromHexString(key.value), 0)
        }

        end <- Async[F].realTime
        _ <- logger.debug(s"Incremental remove completed in ${(end - start).toMillis}ms")
      } yield MerklePatriciaTrie(result).asRight[MerklePatriciaError]
    }

  // ==================== Incremental Insert Operations ====================

  private def insertAt(
    node: MerklePatriciaNode,
    key: CompactNibblePath,
    depth: Int,
    dataDigest: Hash
  ): F[MerklePatriciaNode] =
    node match {
      case branch: MerklePatriciaNode.Branch =>
        insertIntoBranch(branch, key, depth, dataDigest)
      case ext: MerklePatriciaNode.Extension =>
        insertIntoExtension(ext, key, depth, dataDigest)
      case leaf: MerklePatriciaNode.Leaf =>
        insertIntoLeaf(leaf, key, depth, dataDigest)
    }

  private def insertIntoBranch(
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
          insertAt(child, key, depth + 1, dataDigest).map { updatedChild =>
            branch.withUpdatedChild(nibble, updatedChild)
          }
        case None =>
          val remaining = if (depth + 1 >= key.length) CompactNibblePath.empty else key.drop(depth + 1)
          MerklePatriciaNode.Leaf.fromDataDigest[F](remaining, dataDigest).map { newLeaf =>
            branch.withUpdatedChild(nibble, newLeaf)
          }
      }
    }

  private def insertIntoExtension(
    ext: MerklePatriciaNode.Extension,
    key: CompactNibblePath,
    depth: Int,
    dataDigest: Hash
  ): F[MerklePatriciaNode] = {
    val shared = ext.sharedPath
    val keyRemaining = if (depth >= key.length) CompactNibblePath.empty else key.drop(depth)
    val commonLen = shared.commonPrefixLength(keyRemaining)

    if (commonLen == shared.length) {
      insertAt(ext.child, key, depth + shared.length, dataDigest).map { updatedChild =>
        ext.withUpdatedChild(updatedChild.asInstanceOf[MerklePatriciaNode.Branch])
      }
    } else {
      splitExtension(ext, keyRemaining, dataDigest, commonLen)
    }
  }

  private def splitExtension(
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

  private def insertIntoLeaf(
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

  // ==================== Incremental Remove Operations ====================

  private def removeAt(
    node: MerklePatriciaNode,
    key: CompactNibblePath,
    depth: Int
  ): F[MerklePatriciaNode] =
    node match {
      case branch: MerklePatriciaNode.Branch =>
        removeFromBranch(branch, key, depth)
      case ext: MerklePatriciaNode.Extension =>
        removeFromExtension(ext, key, depth)
      case leaf: MerklePatriciaNode.Leaf =>
        val leafKey = leaf.remainingPath
        val keyRemaining = if (depth >= key.length) CompactNibblePath.empty else key.drop(depth)
        if (leafKey == keyRemaining) {
          MerklePatriciaNode.Branch.empty[F].widen
        } else {
          leaf.pure[F].widen
        }
    }

  private def removeFromBranch(
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
          removeAt(child, key, depth + 1).flatMap {
            case b: MerklePatriciaNode.Branch if b.childCount == 0 =>
              val newBranch = branch.withRemovedChild(nibble)
              collapseIfNeeded(newBranch)
            case updatedChild =>
              collapseIfNeeded(branch.withUpdatedChild(nibble, updatedChild))
          }
        case None =>
          branch.pure[F].widen
      }
    }

  private def removeFromExtension(
    ext: MerklePatriciaNode.Extension,
    key: CompactNibblePath,
    depth: Int
  ): F[MerklePatriciaNode] = {
    val shared = ext.sharedPath
    val keyRemaining = if (depth >= key.length) CompactNibblePath.empty else key.drop(depth)

    if (keyRemaining.startsWith(shared)) {
      removeAt(ext.child, key, depth + shared.length).flatMap {
        case b: MerklePatriciaNode.Branch if b.childCount == 0 =>
          MerklePatriciaNode.Branch.empty[F].widen
        case b: MerklePatriciaNode.Branch if b.childCount == 1 =>
          collapseExtensionWithBranch(shared, b)
        case b: MerklePatriciaNode.Branch =>
          ext.withUpdatedChild(b).pure[F].widen
        case _ =>
          ext.pure[F].widen
      }
    } else {
      ext.pure[F].widen
    }
  }

  private def collapseIfNeeded(branch: MerklePatriciaNode.Branch): F[MerklePatriciaNode] =
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

  private def collapseExtensionWithBranch(
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

  // ==================== Legacy Build Tree (Json-based) ====================

  private def batchComputeDataHashes(entries: Array[(CompactNibblePath, Json)]): F[Array[String]] = {
    val numEntries = entries.length
    val batchSize = math.max(100, numEntries / (maxThreads * 4))

    for {
      results <- Async[F].delay(new Array[String](numEntries))
      batches = entries.indices.toList.grouped(batchSize).toList
      _ <- batches.parTraverse_ { batch =>
        batch.traverse_ { i =>
          Hasher[F].hash(entries(i)._2).flatMap { hash =>
            Async[F].delay(results(i) = hash.value)
          }
        }
      }
    } yield results
  }

  private def buildTreeWithHashes(
    entries: Array[(CompactNibblePath, Json)],
    dataHashes: Array[String],
    start: Int,
    end: Int,
    depth: Int
  ): F[MerklePatriciaNode] = {
    val size = end - start

    if (size == 0) {
      MerklePatriciaNode.Branch.empty[F].widen
    } else if (size == 1) {
      val (path, _) = entries(start)
      val dataHash = Hash(dataHashes(start))
      val remaining = if (depth >= path.length) CompactNibblePath.empty else path.drop(depth)
      MerklePatriciaNode.Leaf.fromDataDigest[F](remaining, dataHash).widen
    } else {
      buildWithGroupsHashed(entries, dataHashes, start, end, depth)
    }
  }

  private def buildWithGroupsHashed(
    entries: Array[(CompactNibblePath, Json)],
    dataHashes: Array[String],
    start: Int,
    end: Int,
    depth: Int
  ): F[MerklePatriciaNode] = {
    val groups = findGroups(entries, start, end, depth)

    if (groups.length == 1) {
      val (nibbleValue, gs, ge) = groups(0)
      buildTreeWithHashes(entries, dataHashes, gs, ge, depth + 1)
        .flatMap(child => createSingleGroupNode(nibbleValue, child))
    } else {
      val useParallel = depth <= ParallelDepthThreshold || groups.length >= 4

      val buildChildren: F[List[(Byte, MerklePatriciaNode)]] =
        if (useParallel) {
          groups.toList.parTraverse {
            case (nibbleValue, gs, ge) =>
              buildTreeWithHashes(entries, dataHashes, gs, ge, depth + 1).map(nibbleValue -> _)
          }
        } else {
          groups.toList.traverse {
            case (nibbleValue, gs, ge) =>
              buildTreeWithHashes(entries, dataHashes, gs, ge, depth + 1).map(nibbleValue -> _)
          }
        }

      buildChildren.flatMap { children =>
        MerklePatriciaNode.Branch.fromByteKeys[F](children.toMap).widen
      }
    }
  }

  private def createSingleGroupNode(
    nibbleValue: Byte,
    child: MerklePatriciaNode
  ): F[MerklePatriciaNode] =
    child match {
      case branch: MerklePatriciaNode.Branch =>
        MerklePatriciaNode.Extension.fromCompact[F](CompactNibblePath.single(nibbleValue), branch).widen

      case ext: MerklePatriciaNode.Extension =>
        MerklePatriciaNode.Extension
          .fromCompact[F](
            CompactNibblePath.single(nibbleValue) ++ ext.sharedPath,
            ext.child
          )
          .widen

      case leaf: MerklePatriciaNode.Leaf =>
        MerklePatriciaNode.Leaf
          .fromDataDigest[F](
            CompactNibblePath.single(nibbleValue) ++ leaf.remainingPath,
            leaf.dataDigest
          )
          .widen
    }

  private def prepareAndSort[A: Encoder](data: Map[Hex, A]): Array[(CompactNibblePath, Json)] = {
    val dataArray = data.toArray
    val results = new Array[(CompactNibblePath, Json)](dataArray.length)
    val numThreads = maxThreads

    val chunkSize = math.max(1, (dataArray.length + numThreads - 1) / numThreads)
    val executor = Executors.newFixedThreadPool(numThreads)
    val latch = new CountDownLatch(numThreads)

    for (i <- 0 until numThreads) {
      val startIdx = i * chunkSize
      val endIdx = math.min(startIdx + chunkSize, dataArray.length)
      executor.submit(new Runnable {
        def run(): Unit =
          try {
            var j = startIdx
            while (j < endIdx) {
              val (hex, value) = dataArray(j)
              results(j) = (CompactNibblePath.fromHexString(hex.value), value.asJson)
              j += 1
            }
          } finally
            latch.countDown()
      })
    }
    latch.await()
    executor.shutdown()

    java.util.Arrays.parallelSort(
      results,
      (a: (CompactNibblePath, Json), b: (CompactNibblePath, Json)) => CompactNibblePath.ordering.compare(a._1, b._1)
    )

    results
  }

  @inline private def findGroups(
    entries: Array[(CompactNibblePath, Json)],
    start: Int,
    end: Int,
    depth: Int
  ): mutable.ArrayBuffer[(Byte, Int, Int)] = {
    val groups = mutable.ArrayBuffer[(Byte, Int, Int)]()
    var groupStart = start
    var currentNibble = entries(start)._1.getOrEmpty(depth)

    var i = start + 1
    while (i < end) {
      val nibble = entries(i)._1.getOrEmpty(depth)
      if (nibble != currentNibble) {
        groups += ((currentNibble, groupStart, i))
        groupStart = i
        currentNibble = nibble
      }
      i += 1
    }
    groups += ((currentNibble, groupStart, end))
    groups
  }
}

object ParallelMerklePatriciaProducer {
  def apply[F[_]: Hasher: Async: Parallel]: ParallelMerklePatriciaProducer[F] =
    new ParallelMerklePatriciaProducer[F]()
}
