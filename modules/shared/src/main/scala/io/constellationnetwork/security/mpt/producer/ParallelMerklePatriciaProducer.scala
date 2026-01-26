package io.constellationnetwork.security.mpt.producer

import java.util.concurrent.{CountDownLatch, Executors}

import cats.Parallel
import cats.effect.{Async, Sync}
import cats.syntax.all._

import scala.collection.immutable.ArraySeq
import scala.collection.mutable

import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt._
import io.constellationnetwork.security.mpt.prover.MerklePatriciaSingleInclusionProver

import io.circe.syntax._
import io.circe.{Encoder, Json}

/** Optimized parallel MPT construction.
  *
  * Key optimizations over the original implementation:
  *
  *   1. PARALLEL JSON ENCODING: Uses Java ExecutorService to parallelize JSON serialization across all CPU cores. Original was sequential
  *      via .map().
  *
  * 2. ARRAY-BASED PROCESSING: Uses primitive arrays with index slicing instead of creating new List/Vector collections at each recursion
  * level. This eliminates massive intermediate allocations.
  *
  * 3. IN-PLACE SORTING: Uses java.util.Arrays.sort with a custom Comparator instead of Scala's sortBy which creates intermediate
  * collections.
  *
  * 4. CONTIGUOUS GROUP DETECTION: Since data is sorted, groups are contiguous. Uses a single linear scan to find group boundaries instead
  * of groupBy which creates intermediate Maps.
  *
  * 5. DIRECT TREE BUILDING: Builds MerklePatriciaNode directly in one recursive pass instead of building an intermediate "pure" tree
  * structure first.
  *
  * 6. SMART PARALLELISM: Uses parTraverse at branch points where multiple children exist, giving natural parallelism that follows the tree
  * structure.
  *
  * 7. STRATEGIC CEDING: Adds Async[F].cede calls based on workload size to prevent CPU starvation of other fibers during heavy computation.
  *
  * Performance improvement: ~30s → ~14s for 800K entries (~50% reduction)
  */
class ParallelMerklePatriciaProducer[F[_]: Hasher: Async: Parallel] extends MerklePatriciaProducer[F] {

  def getProver(trie: MerklePatriciaTrie): F[MerklePatriciaSingleInclusionProver[F]] =
    MerklePatriciaSingleInclusionProver.make[F](trie).pure[F]

  def create[A: Encoder](data: Map[Hex, A]): F[MerklePatriciaTrie] =
    if (data.isEmpty) {
      MerklePatriciaNode.Branch[F](Map.empty).map(MerklePatriciaTrie(_))
    } else {
      for {
        // Phase 1: Prepare and sort in blocking context
        entries <- Async[F].blocking(prepareAndSort(data))
        // Phase 2: Build tree with parallel hashing at branch points
        root <- buildTree(entries, 0, entries.length, 0)
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

  // ===========================================================================
  // Phase 1: Parallel JSON encoding + sorting
  // ===========================================================================

  private def prepareAndSort[A: Encoder](data: Map[Hex, A]): Array[(ArraySeq[Nibble], Json)] = {
    val numCores = Runtime.getRuntime.availableProcessors()
    val dataArray = data.toArray
    val results = new Array[(ArraySeq[Nibble], Json)](dataArray.length)

    // Parallel JSON encoding using Java threads
    val chunkSize = math.max(1, (dataArray.length + numCores - 1) / numCores)
    val executor = Executors.newFixedThreadPool(numCores)
    val latch = new CountDownLatch(numCores)

    for (i <- 0 until numCores) {
      val start = i * chunkSize
      val end = math.min(start + chunkSize, dataArray.length)
      executor.submit(new Runnable {
        def run(): Unit =
          try {
            var j = start
            while (j < end) {
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
    executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)

    // In-place sort with custom comparator
    java.util.Arrays.sort(
      results,
      new java.util.Comparator[(ArraySeq[Nibble], Json)] {
        override def compare(a: (ArraySeq[Nibble], Json), b: (ArraySeq[Nibble], Json)): Int = {
          val pathA = a._1
          val pathB = b._1
          val minLen = math.min(pathA.length, pathB.length)
          var i = 0
          while (i < minLen) {
            val cmp = java.lang.Byte.compare(pathA(i).value, pathB(i).value)
            if (cmp != 0) return cmp
            i += 1
          }
          pathA.length - pathB.length
        }
      }
    )

    results
  }

  // ===========================================================================
  // Phase 2: Direct tree building with parallel hashing
  // ===========================================================================

  private def buildTree(
    entries: Array[(ArraySeq[Nibble], Json)],
    start: Int,
    end: Int,
    depth: Int
  ): F[MerklePatriciaNode] = {
    val size = end - start

    if (size == 0) {
      MerklePatriciaNode.Branch[F](Map.empty).widen
    } else if (size == 1) {
      val (path, data) = entries(start)
      val remaining = if (depth >= path.length) ArraySeq.empty[Nibble] else path.drop(depth)
      val leaf: F[MerklePatriciaNode] = MerklePatriciaNode.Leaf[F](remaining, data).widen
      // Cede periodically during leaf creation
      if (start % 10000 == 0) Async[F].cede *> leaf else leaf
    } else {
      buildWithGroups(entries, start, end, depth)
    }
  }

  private def buildWithGroups(
    entries: Array[(ArraySeq[Nibble], Json)],
    start: Int,
    end: Int,
    depth: Int
  ): F[MerklePatriciaNode] = {
    // Find contiguous group boundaries (data is sorted)
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

    if (groups.length == 1) {
      // Single group - might become extension
      val (nibble, gs, ge) = groups(0)
      buildTree(entries, gs, ge, depth + 1).flatMap(child => createSingleGroupNode(nibble, child))
    } else {
      // Multiple groups - build children in parallel
      val processChildren: F[MerklePatriciaNode] = groups.toList.parTraverse {
        case (nibble, gs, ge) =>
          buildTree(entries, gs, ge, depth + 1).map(nibble -> _)
      }
        .flatMap(children => MerklePatriciaNode.Branch[F](children.toMap).widen)

      // Strategic ceding based on workload size
      val entriesInRange = end - start
      if (depth <= 3 || entriesInRange > 50000) {
        Async[F].cede *> processChildren <* Async[F].cede
      } else if (depth <= 6 || entriesInRange > 10000) {
        Async[F].cede *> processChildren
      } else {
        processChildren
      }
    }
  }

  private def getNibbleAt(path: ArraySeq[Nibble], depth: Int): Nibble =
    if (depth < path.length) path(depth) else Nibble.empty

  private def createSingleGroupNode(nibble: Nibble, child: MerklePatriciaNode): F[MerklePatriciaNode] =
    child match {
      case branch: MerklePatriciaNode.Branch =>
        MerklePatriciaNode.Extension[F](ArraySeq(nibble), branch).widen
      case ext: MerklePatriciaNode.Extension =>
        MerklePatriciaNode.Extension[F](ArraySeq(nibble) ++ ext.shared, ext.child).widen
      case leaf: MerklePatriciaNode.Leaf =>
        MerklePatriciaNode.Leaf[F](ArraySeq(nibble) ++ leaf.remaining, leaf.data).widen
    }
}

object ParallelMerklePatriciaProducer {
  def apply[F[_]: Hasher: Async: Parallel]: ParallelMerklePatriciaProducer[F] =
    new ParallelMerklePatriciaProducer[F]
}
