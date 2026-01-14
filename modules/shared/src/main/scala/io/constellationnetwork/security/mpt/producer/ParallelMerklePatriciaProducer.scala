package io.constellationnetwork.security.mpt.producer

import cats.Parallel
import cats.effect.{Async, Sync}
import cats.syntax.all._

import scala.collection.immutable.ArraySeq

import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt._
import io.constellationnetwork.security.mpt.prover.MerklePatriciaSingleInclusionProver

import io.circe.syntax._
import io.circe.{Encoder, Json}

/** Parallel bottom-up MPT construction.
  *
  * Algorithm:
  *   1. Create all leaf nodes in parallel (independent hash computations) 2. Sort leaves by nibble path 3. Recursively group by first
  *      nibble and merge bottom-up
  *      - Groups at each level are independent → parallel processing
  *      - Create Branch for multiple groups, Extension for shared prefixes
  *
  * This produces the SAME tree structure as sequential insertion because MPT structure is deterministic given the set of keys.
  */
class ParallelMerklePatriciaProducer[F[_]: Hasher: Async: Parallel] extends MerklePatriciaProducer[F] {

  // Threshold for batching/blocking CPU-intensive operations to avoid starvation
  private val blockingThreshold = 10000

  def getProver(trie: MerklePatriciaTrie): F[MerklePatriciaSingleInclusionProver[F]] =
    MerklePatriciaSingleInclusionProver.make[F](trie).pure[F]

  def create[A: Encoder](data: Map[Hex, A]): F[MerklePatriciaTrie] =
    if (data.isEmpty) {
      MerklePatriciaNode.Branch[F](Map.empty).map(MerklePatriciaTrie(_))
    } else {
      for {
        // Step 1: Create all leaves
        leaves <- createLeavesParallel(data)

        // Step 2: Sort by nibble path for grouping
        sorted <- (leaves.size > blockingThreshold)
          .pure[F]
          .ifM(
            ifTrue = Async[F].blocking(leaves.sortBy(_._1)(Nibble.nibbleSeqOrdering)),
            ifFalse = Async[F].cede *> leaves.sortBy(_._1)(Nibble.nibbleSeqOrdering).pure[F]
          )

        // Step 3: Build tree bottom-up with parallel merging
        _ <- Async[F].cede
        root <- buildTreeBottomUp(sorted.toList)
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
  // Step 1: Create leaves in parallel
  // ===========================================================================

  private def createLeavesParallel[A: Encoder](
    data: Map[Hex, A]
  ): F[Vector[(Seq[Nibble], Json)]] =
    // Convert to (nibblePath, json) pairs - no hashing yet, just prep
    data.toVector.map {
      case (hex, value) => (Nibble(hex), value.asJson)
    }.pure[F]

  // ===========================================================================
  // Step 3: Bottom-up tree construction
  // ===========================================================================

  /** Build tree bottom-up by recursively grouping by first nibble.
    *
    * For sorted entries with nibble paths, group by first nibble, recursively build subtrees for each group, then combine.
    */
  private def buildTreeBottomUp(
    entries: List[(Seq[Nibble], Json)]
  ): F[MerklePatriciaNode] =
    entries match {
      case Nil =>
        // Empty - return empty branch
        MerklePatriciaNode.Branch[F](Map.empty).widen

      case (path, data) :: Nil =>
        // Single entry - create leaf with remaining path
        MerklePatriciaNode.Leaf[F](path, data).widen

      case multiple =>
        // Multiple entries - group by first nibble and recurse
        groupAndMerge(multiple)
    }

  /** Group entries by first nibble, recursively process each group, then create appropriate node structure.
    */
  private def groupAndMerge(
    entries: List[(Seq[Nibble], Json)]
  ): F[MerklePatriciaNode] =
    for {
      // Group by first nibble - process in batches with cedes for large sets
      strippedGroups <- (entries.size > blockingThreshold)
        .pure[F]
        .ifM(
          ifTrue = groupEntriesWithCede(entries),
          ifFalse = Sync[F].delay {
            entries.groupBy {
              case (path, _) => path.headOption.getOrElse(Nibble.empty)
            }.map {
              case (nibble, group) =>
                (nibble, group.map { case (path, data) => (path.drop(1), data) })
            }
          }
        )

      _ <- Async[F].cede
      // Process each group in parallel
      processedGroups <- strippedGroups.toList.parTraverse {
        case (nibble, group) =>
          buildTreeBottomUp(group).map(node => (nibble, node))
      }
      _ <- Async[F].cede
      result <- createNodeFromGroups(processedGroups)
    } yield result

  /** Group entries with periodic cedes to avoid starvation */
  private def groupEntriesWithCede(
    entries: List[(Seq[Nibble], Json)]
  ): F[Map[Nibble, List[(Seq[Nibble], Json)]]] = {
    // Process in batches, ceding between batches
    val batchSize = 50000
    entries
      .grouped(batchSize)
      .toList
      .traverse { batch =>
        Async[F].cede *> Sync[F].delay {
          batch.groupBy {
            case (path, _) => path.headOption.getOrElse(Nibble.empty)
          }.map {
            case (nibble, group) =>
              (nibble, group.map { case (path, data) => (path.drop(1), data) })
          }
        }
      }
      .map { batchResults =>
        // Merge all batch results
        batchResults.foldLeft(Map.empty[Nibble, List[(Seq[Nibble], Json)]]) { (acc, batch) =>
          batch.foldLeft(acc) {
            case (map, (nibble, entries)) =>
              map.updated(nibble, map.getOrElse(nibble, List.empty) ++ entries)
          }
        }
      }
  }

  /** Create appropriate node structure from processed groups.
    *   - Single group with single-child branch: create Extension
    *   - Multiple groups: create Branch
    */
  private def createNodeFromGroups(
    groups: List[(Nibble, MerklePatriciaNode)]
  ): F[MerklePatriciaNode] =
    groups match {
      case Nil =>
        MerklePatriciaNode.Branch[F](Map.empty).widen

      case (nibble, child) :: Nil =>
        // Single group - might become extension
        child match {
          case branch: MerklePatriciaNode.Branch =>
            // Single path to a branch - create extension
            MerklePatriciaNode.Extension[F](ArraySeq(nibble), branch).widen
          case ext: MerklePatriciaNode.Extension =>
            // Single path to extension - merge into longer extension
            MerklePatriciaNode.Extension[F](ArraySeq(nibble) ++ ext.shared, ext.child).widen
          case leaf: MerklePatriciaNode.Leaf =>
            // Single path to leaf - prepend nibble to leaf's remaining path
            MerklePatriciaNode.Leaf[F](ArraySeq(nibble) ++ leaf.remaining, leaf.data).widen
        }

      case multiple =>
        // Multiple groups - create branch
        MerklePatriciaNode.Branch[F](multiple.toMap).widen
    }
}

object ParallelMerklePatriciaProducer {

  def apply[F[_]: Hasher: Async: Parallel]: ParallelMerklePatriciaProducer[F] =
    new ParallelMerklePatriciaProducer[F]
}
