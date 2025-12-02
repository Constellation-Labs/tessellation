package io.constellationnetwork.security.mpt.prover

import cats.effect.Sync
import cats.syntax.all._

import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt.Nibble.nibbleSeqOrdering
import io.constellationnetwork.security.mpt._
import io.constellationnetwork.security.mpt.prover.attestation.{
  MerklePatriciaInclusionProof,
  MerklePatriciaRangeProof,
  RangeExclusionBoundaries
}

trait MerklePatriciaRangeProver[F[_]] {

  def attestRange(
    startPath: Hex,
    endPath: Hex
  ): F[Either[MerklePatriciaProofError, MerklePatriciaRangeProof]]
}

object MerklePatriciaRangeProver {
  def apply[F[_]](implicit prover: MerklePatriciaRangeProver[F]): MerklePatriciaRangeProver[F] = prover

  def make[F[_]: Sync: Hasher](
    trie: MerklePatriciaTrie
  ): MerklePatriciaRangeProver[F] =
    new MerklePatriciaRangeProver[F] {

      def attestRange(
        startPath: Hex,
        endPath: Hex
      ): F[Either[MerklePatriciaProofError, MerklePatriciaRangeProof]] = {

        case class CollectedLeaf(path: Hex, leaf: MerklePatriciaNode.Leaf)

        def hexOrdering: Ordering[Hex] = Ordering.by[Hex, String](_.value)

        def collectLeavesInRange(
          node: MerklePatriciaNode,
          currentPath: Seq[Nibble],
          startNibbles: Seq[Nibble],
          endNibbles: Seq[Nibble],
          acc: List[CollectedLeaf]
        ): F[List[CollectedLeaf]] =
          node match {
            case leaf: MerklePatriciaNode.Leaf =>
              val fullPath = currentPath ++ leaf.remaining
              val fullPathHex = Nibble.toHex(fullPath)

              (nibbleSeqOrdering.gteq(fullPath, startNibbles) && nibbleSeqOrdering.lteq(fullPath, endNibbles))
                .pure[F]
                .ifM(
                  ifTrue = (CollectedLeaf(fullPathHex, leaf) :: acc).pure[F],
                  ifFalse = acc.pure[F]
                )

            case extension: MerklePatriciaNode.Extension =>
              val extendedPath = currentPath ++ extension.shared

              shouldExploreSubtree(extendedPath, startNibbles, endNibbles)
                .pure[F]
                .ifM(
                  ifTrue = collectLeavesInRange(extension.child, extendedPath, startNibbles, endNibbles, acc),
                  ifFalse = acc.pure[F]
                )

            case branch: MerklePatriciaNode.Branch =>
              branch.paths.toList.foldLeftM(acc) {
                case (currentAcc, (nibble, child)) =>
                  val childPath = currentPath :+ nibble
                  shouldExploreSubtree(childPath, startNibbles, endNibbles)
                    .pure[F]
                    .ifM(
                      ifTrue = collectLeavesInRange(child, childPath, startNibbles, endNibbles, currentAcc),
                      ifFalse = currentAcc.pure[F]
                    )
              }
          }

        def shouldExploreSubtree(
          nodePath: Seq[Nibble],
          startNibbles: Seq[Nibble],
          endNibbles: Seq[Nibble]
        ): Boolean = {
          val startPrefix = startNibbles.take(nodePath.length)
          val endPrefix = endNibbles.take(nodePath.length)

          nibbleSeqOrdering.lteq(nodePath, endPrefix) && (
            nibbleSeqOrdering.gteq(nodePath, startPrefix) || startPrefix.startsWith(nodePath)
          )
        }

        def findBoundaryLeaf(target: Hex, findNext: Boolean): F[Option[MerklePatriciaInclusionProof]] = {
          val allLeavesWithPaths = MerklePatriciaTrie.collectLeafNodesWithPaths(trie)
          val sortedPaths = allLeavesWithPaths.map(_._1).sorted(hexOrdering)

          val boundary = if (findNext) {
            sortedPaths.find(path => hexOrdering.compare(path, target) > 0)
          } else {
            sortedPaths.findLast(path => hexOrdering.compare(path, target) < 0)
          }

          boundary match {
            case Some(path) =>
              val singleProver = MerklePatriciaSingleInclusionProver.make[F](trie)
              singleProver.attestPath(path).map(_.toOption)
            case None =>
              none[MerklePatriciaInclusionProof].pure[F]
          }
        }

        def buildRangeProof(leaves: List[CollectedLeaf]): F[Either[MerklePatriciaProofError, MerklePatriciaRangeProof]] = {
          val singleProver = MerklePatriciaSingleInclusionProver.make[F](trie)
          val paths = leaves.map(_.path).sorted(hexOrdering)

          for {
            inclusionProofs <- paths.traverse(path => singleProver.attestPath(path))
            leftBoundary <- findBoundaryLeaf(startPath, findNext = false)
            rightBoundary <- findBoundaryLeaf(endPath, findNext = true)
          } yield
            inclusionProofs.sequence.map { proofs =>
              val exclusionBoundaries: Option[RangeExclusionBoundaries] = if (leftBoundary.nonEmpty || rightBoundary.nonEmpty) {
                Some(RangeExclusionBoundaries(leftBoundary, rightBoundary))
              } else {
                None
              }

              MerklePatriciaRangeProof(startPath, endPath, proofs, exclusionBoundaries)
            }
        }

        val startNibbles = Nibble(startPath)
        val endNibbles = Nibble(endPath)

        (hexOrdering.compare(startPath, endPath) > 0)
          .pure[F]
          .ifM(
            ifTrue =
              (ProofGenerationError(s"Invalid range: startPath ${startPath.value} > endPath ${endPath.value}"): MerklePatriciaProofError)
                .asLeft[MerklePatriciaRangeProof]
                .pure[F],
            ifFalse = for {
              leaves <- collectLeavesInRange(trie.rootNode, Seq.empty, startNibbles, endNibbles, List.empty)
              result <- leaves.isEmpty
                .pure[F]
                .ifM(
                  ifTrue = for {
                    leftBoundary <- findBoundaryLeaf(startPath, findNext = false)
                    rightBoundary <- findBoundaryLeaf(endPath, findNext = true)
                  } yield {
                    val boundaries: Option[RangeExclusionBoundaries] = if (leftBoundary.nonEmpty || rightBoundary.nonEmpty) {
                      Some(RangeExclusionBoundaries(leftBoundary, rightBoundary))
                    } else {
                      None
                    }
                    (MerklePatriciaRangeProof(startPath, endPath, List.empty, boundaries): MerklePatriciaRangeProof)
                      .asRight[MerklePatriciaProofError]
                  },
                  ifFalse = buildRangeProof(leaves.reverse)
                )
            } yield result
          )
      }.handleError(e => ProofGenerationError(e.getMessage).asLeft[MerklePatriciaRangeProof])
    }

  object syntax {

    implicit class MerklePatriciaRangeOps(private val startPath: Hex) extends AnyVal {

      def attestRangeInclusion[F[_]](
        endPath: Hex
      )(implicit P: MerklePatriciaRangeProver[F]): F[Either[MerklePatriciaProofError, MerklePatriciaRangeProof]] =
        P.attestRange(startPath, endPath)
    }
  }
}
