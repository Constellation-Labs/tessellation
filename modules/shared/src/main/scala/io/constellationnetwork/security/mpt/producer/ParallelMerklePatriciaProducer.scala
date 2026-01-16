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

class ParallelMerklePatriciaProducer[F[_]: Hasher: Async: Parallel](
  maxThreads: Int = Runtime.getRuntime.availableProcessors()
) extends MerklePatriciaProducer[F] {

  private val logger = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

  import java.util.concurrent.{CountDownLatch, Executors}

  private val ParallelDepthThreshold = 10

  def getProver(trie: MerklePatriciaTrie): F[MerklePatriciaSingleInclusionProver[F]] =
    MerklePatriciaSingleInclusionProver.make[F](trie).pure[F]

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

  private def batchComputeDataHashes(entries: Array[(ArraySeq[Nibble], Json)]): F[Array[String]] = {
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
    entries: Array[(ArraySeq[Nibble], Json)],
    dataHashes: Array[String],
    start: Int,
    end: Int,
    depth: Int
  ): F[MerklePatriciaNode] = {
    val size = end - start

    if (size == 0) {
      MerklePatriciaNode.Branch[F](Map.empty).widen
    } else if (size == 1) {
      val (path, data) = entries(start)
      val dataHash = Hash(dataHashes(start))
      val remaining = if (depth >= path.length) ArraySeq.empty[Nibble] else path.drop(depth)
      MerklePatriciaNode.Leaf.withDataDigest[F](remaining, data, dataHash).widen
    } else {
      buildWithGroupsHashed(entries, dataHashes, start, end, depth)
    }
  }

  private def buildWithGroupsHashed(
    entries: Array[(ArraySeq[Nibble], Json)],
    dataHashes: Array[String],
    start: Int,
    end: Int,
    depth: Int
  ): F[MerklePatriciaNode] = {
    val groups = findGroups(entries, start, end, depth)

    if (groups.length == 1) {
      val (nibble, gs, ge) = groups(0)
      buildTreeWithHashes(entries, dataHashes, gs, ge, depth + 1)
        .flatMap(child => createSingleGroupNode(nibble, child, dataHashes, gs))
    } else {
      val useParallel = depth <= ParallelDepthThreshold || groups.length >= 4

      val buildChildren: F[List[(Nibble, MerklePatriciaNode)]] =
        if (useParallel) {
          groups.toList.parTraverse {
            case (nibble, gs, ge) =>
              buildTreeWithHashes(entries, dataHashes, gs, ge, depth + 1).map(nibble -> _)
          }
        } else {
          groups.toList.traverse {
            case (nibble, gs, ge) =>
              buildTreeWithHashes(entries, dataHashes, gs, ge, depth + 1).map(nibble -> _)
          }
        }

      buildChildren.flatMap { children =>
        MerklePatriciaNode.Branch[F](children.toMap).widen
      }
    }
  }

  private def createSingleGroupNode(
    nibble: Nibble,
    child: MerklePatriciaNode,
    dataHashes: Array[String],
    entryIndex: Int
  ): F[MerklePatriciaNode] =
    child match {
      case branch: MerklePatriciaNode.Branch =>
        MerklePatriciaNode.Extension[F](ArraySeq(nibble), branch).widen

      case ext: MerklePatriciaNode.Extension =>
        MerklePatriciaNode.Extension[F](ArraySeq(nibble) ++ ext.shared, ext.child).widen

      case leaf: MerklePatriciaNode.Leaf =>
        MerklePatriciaNode.Leaf
          .withDataDigest[F](
            ArraySeq(nibble) ++ leaf.remaining,
            leaf.data,
            Hash(dataHashes(entryIndex))
          )
          .widen
    }

  private def prepareAndSort[A: Encoder](data: Map[Hex, A]): Array[(ArraySeq[Nibble], Json)] = {
    val dataArray = data.toArray
    val results = new Array[(ArraySeq[Nibble], Json)](dataArray.length)
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
              results(j) = (ArraySeq.unsafeWrapArray(Nibble(hex).toArray), value.asJson)
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
      (a: (ArraySeq[Nibble], Json), b: (ArraySeq[Nibble], Json)) => compareNibblePaths(a._1, b._1)
    )

    results
  }

  @inline private def compareNibblePaths(pathA: ArraySeq[Nibble], pathB: ArraySeq[Nibble]): Int = {
    val minLen = math.min(pathA.length, pathB.length)
    var i = 0
    while (i < minLen) {
      val cmp = java.lang.Byte.compare(pathA(i).value, pathB(i).value)
      if (cmp != 0) return cmp
      i += 1
    }
    pathA.length - pathB.length
  }

  @inline private def findGroups(
    entries: Array[(ArraySeq[Nibble], Json)],
    start: Int,
    end: Int,
    depth: Int
  ): mutable.ArrayBuffer[(Nibble, Int, Int)] = {
    val groups = mutable.ArrayBuffer[(Nibble, Int, Int)]()
    var groupStart = start
    var currentNibble = getNibbleAt(entries(start)._1, depth)

    var i = start + 1
    while (i < end) {
      val nibble = getNibbleAt(entries(i)._1, depth)
      if (nibble.value != currentNibble.value) {
        groups += ((currentNibble, groupStart, i))
        groupStart = i
        currentNibble = nibble
      }
      i += 1
    }
    groups += ((currentNibble, groupStart, end))
    groups
  }

  @inline private def getNibbleAt(path: ArraySeq[Nibble], depth: Int): Nibble =
    if (depth < path.length) path(depth) else Nibble.empty
}

object ParallelMerklePatriciaProducer {
  def apply[F[_]: Hasher: Async: Parallel]: ParallelMerklePatriciaProducer[F] =
    new ParallelMerklePatriciaProducer[F]()
}
