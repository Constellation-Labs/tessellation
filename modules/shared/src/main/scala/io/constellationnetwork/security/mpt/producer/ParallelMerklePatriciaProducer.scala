package io.constellationnetwork.security.mpt.producer

import java.util.concurrent.{CountDownLatch, Executors}

import cats.Parallel
import cats.effect.{Async, Ref}
import cats.syntax.all._

import scala.collection.immutable.ArraySeq
import scala.collection.mutable

import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt._
import io.constellationnetwork.security.mpt.prover.MerklePatriciaSingleInclusionProver

import io.circe.syntax._
import io.circe.{Encoder, Json}
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Result of parallel trie creation with external data storage. */
case class ParallelTrieWithData(
  trie: MerklePatriciaTrie,
  dataStore: Map[Hash, Json]
)

class ParallelMerklePatriciaProducer[F[_]: Hasher: Async: Parallel](
  maxThreads: Int = Runtime.getRuntime.availableProcessors()
) extends MerklePatriciaProducer[F] {

  private val logger = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

  private val ParallelDepthThreshold = 10

  def getProver(trie: MerklePatriciaTrie): F[MerklePatriciaSingleInclusionProver[F]] =
    MerklePatriciaSingleInclusionProver.make[F](trie).pure[F]

  /** Create trie with external data storage for memory efficiency. */
  def createWithData[A: Encoder](data: Map[Hex, A]): F[ParallelTrieWithData] =
    if (data.isEmpty) {
      MerklePatriciaNode.Branch[F](Map.empty).map(node => ParallelTrieWithData(MerklePatriciaTrie(node), Map.empty))
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

        // Build data store from hashes
        dataStore = buildDataStore(entries, dataHashes)

        buildStart <- Async[F].realTime
        root <- buildTreeWithHashes(entries, dataHashes, 0, entries.length, 0)
        buildEnd <- Async[F].realTime
        _ <- logger.info(s"Phase 3 - buildTree took ${(buildEnd - buildStart).toMillis}ms")

        totalEnd <- Async[F].realTime
        _ <- logger.info(s"Total MPT creation took ${(totalEnd - totalStart).toMillis}ms")
      } yield ParallelTrieWithData(MerklePatriciaTrie(root), dataStore)
    }

  /** Original create method - returns trie without data store for backward compatibility. */
  def create[A: Encoder](data: Map[Hex, A]): F[MerklePatriciaTrie] =
    if (data.isEmpty) {
      MerklePatriciaNode.Branch[F](Map.empty).map(MerklePatriciaTrie(_))
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

  def insert[A: Encoder](
    current: MerklePatriciaTrie,
    data: Map[Hex, A]
  ): F[Either[MerklePatriciaError, MerklePatriciaTrie]] =
    new StatelessMerklePatriciaProducer[F].insert(current, data)

  def remove(
    current: MerklePatriciaTrie,
    data: List[Hex]
  ): F[Either[MerklePatriciaError, MerklePatriciaTrie]] =
    new StatelessMerklePatriciaProducer[F].remove(current, data)

  /** Build data store mapping Hash -> Json from entries and computed hashes. */
  private def buildDataStore(
    entries: Array[(CompactNibblePath, Json)],
    dataHashes: Array[String]
  ): Map[Hash, Json] = {
    val builder = Map.newBuilder[Hash, Json]
    builder.sizeHint(entries.length)
    var i = 0
    while (i < entries.length) {
      builder += (Hash(dataHashes(i)) -> entries(i)._2)
      i += 1
    }
    builder.result()
  }

  /** Batch compute data hashes. Uses CompactNibblePath entries. */
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

  /** Build tree using CompactNibblePath for memory efficiency. Now creates Leaf nodes with only dataDigest, not the full Json data.
    */
  private def buildTreeWithHashes(
    entries: Array[(CompactNibblePath, Json)],
    dataHashes: Array[String],
    start: Int,
    end: Int,
    depth: Int
  ): F[MerklePatriciaNode] = {
    val size = end - start

    if (size == 0) {
      MerklePatriciaNode.Branch[F](Map.empty).widen
    } else if (size == 1) {
      val (path, _) = entries(start)
      val dataHash = Hash(dataHashes(start))
      val remaining = if (depth >= path.length) CompactNibblePath.empty else path.drop(depth)
      // Use fromDataDigest - don't store the Json data in the leaf
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
        .flatMap(child => createSingleGroupNode(nibbleValue, child, dataHashes, gs))
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

  /** Create node for single group. Uses byte nibble values and CompactNibblePath. Now uses dataDigest only for Leaf nodes.
    */
  private def createSingleGroupNode(
    nibbleValue: Byte,
    child: MerklePatriciaNode,
    dataHashes: Array[String],
    entryIndex: Int
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
        // Use fromDataDigest with the leaf's existing dataDigest
        MerklePatriciaNode.Leaf
          .fromDataDigest[F](
            CompactNibblePath.single(nibbleValue) ++ leaf.remainingPath,
            leaf.dataDigest
          )
          .widen
    }

  /** Prepare and sort data using CompactNibblePath.
    *
    * This is the main memory optimization - we now store paths as packed bytes instead of boxed Nibble objects.
    */
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

  /** Find groups by nibble at given depth. Returns groups with Byte nibble values instead of Nibble objects. */
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
