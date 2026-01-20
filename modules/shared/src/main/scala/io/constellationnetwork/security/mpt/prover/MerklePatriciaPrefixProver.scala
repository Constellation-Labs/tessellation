package io.constellationnetwork.security.mpt.prover

import cats.effect.Sync
import cats.effect.kernel.Async
import cats.syntax.all._

import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt._
import io.constellationnetwork.security.mpt.prover.attestation.MerklePatriciaBatchInclusionProof

trait MerklePatriciaPrefixProver[F[_]] {

  def attestPrefix(prefix: Hex): F[Either[MerklePatriciaProofError, MerklePatriciaBatchInclusionProof]]
}

object MerklePatriciaPrefixProver {
  def apply[F[_]](implicit prover: MerklePatriciaPrefixProver[F]): MerklePatriciaPrefixProver[F] = prover

  def make[F[_]: Async: Hasher](
    trie: MerklePatriciaTrie
  ): MerklePatriciaPrefixProver[F] =
    new MerklePatriciaPrefixProver[F] {

      def attestPrefix(prefix: Hex): F[Either[MerklePatriciaProofError, MerklePatriciaBatchInclusionProof]] = {

        case class CollectedLeaf(path: Hex, leaf: MerklePatriciaNode.Leaf)

        def collectLeavesUnderPrefix(
          node: MerklePatriciaNode,
          currentPath: Seq[Nibble],
          targetPrefix: Seq[Nibble],
          acc: List[CollectedLeaf]
        ): F[List[CollectedLeaf]] =
          node match {
            case leaf: MerklePatriciaNode.Leaf =>
              val fullPath = currentPath ++ leaf.remaining
              fullPath
                .startsWith(targetPrefix)
                .pure[F]
                .ifM(
                  ifTrue = (CollectedLeaf(Nibble.toHex(fullPath), leaf) :: acc).pure[F],
                  ifFalse = acc.pure[F]
                )

            case extension: MerklePatriciaNode.Extension =>
              val extendedPath = currentPath ++ extension.shared

              targetPrefix
                .startsWith(extendedPath)
                .pure[F]
                .ifM(
                  ifTrue = collectLeavesUnderPrefix(extension.child, extendedPath, targetPrefix, acc),
                  ifFalse = extendedPath
                    .startsWith(targetPrefix)
                    .pure[F]
                    .ifM(
                      ifTrue = collectAllLeavesUnder(extension.child, extendedPath, acc),
                      ifFalse = acc.pure[F]
                    )
                )

            case branch: MerklePatriciaNode.Branch =>
              targetPrefix
                .startsWith(currentPath)
                .pure[F]
                .ifM(
                  ifTrue = {
                    val prefixRemaining = targetPrefix.drop(currentPath.length)
                    prefixRemaining.isEmpty
                      .pure[F]
                      .ifM(
                        ifTrue = branch.paths.toList.foldLeftM(acc) {
                          case (currentAcc, (nibble, child)) =>
                            collectAllLeavesUnder(child, currentPath :+ nibble, currentAcc)
                        },
                        ifFalse = branch.paths.get(prefixRemaining.head) match {
                          case Some(child) =>
                            collectLeavesUnderPrefix(child, currentPath :+ prefixRemaining.head, targetPrefix, acc)
                          case None =>
                            acc.pure[F]
                        }
                      )
                  },
                  ifFalse = currentPath
                    .startsWith(targetPrefix)
                    .pure[F]
                    .ifM(
                      ifTrue = branch.paths.toList.foldLeftM(acc) {
                        case (currentAcc, (nibble, child)) =>
                          collectAllLeavesUnder(child, currentPath :+ nibble, currentAcc)
                      },
                      ifFalse = acc.pure[F]
                    )
                )
          }

        def collectAllLeavesUnder(
          node: MerklePatriciaNode,
          currentPath: Seq[Nibble],
          acc: List[CollectedLeaf]
        ): F[List[CollectedLeaf]] =
          node match {
            case leaf: MerklePatriciaNode.Leaf =>
              val fullPath = currentPath ++ leaf.remaining
              (CollectedLeaf(Nibble.toHex(fullPath), leaf) :: acc).pure[F]

            case extension: MerklePatriciaNode.Extension =>
              collectAllLeavesUnder(extension.child, currentPath ++ extension.shared, acc)

            case branch: MerklePatriciaNode.Branch =>
              branch.paths.toList.foldLeftM(acc) {
                case (currentAcc, (nibble, child)) =>
                  collectAllLeavesUnder(child, currentPath :+ nibble, currentAcc)
              }
          }

        def buildBatchProof(leaves: List[CollectedLeaf]): F[Either[MerklePatriciaProofError, MerklePatriciaBatchInclusionProof]] = {
          val batchProver = MerklePatriciaBatchInclusionProver.make[F](trie)
          val paths = leaves.map(_.path)
          batchProver.attestPaths(paths)
        }

        val prefixNibbles = Nibble(prefix)
        for {
          leaves <- collectLeavesUnderPrefix(trie.rootNode, Seq.empty, prefixNibbles, List.empty)
          result <- leaves.isEmpty
            .pure[F]
            .ifM(
              ifTrue = (PathNotFound(s"No paths found with prefix: ${prefix.value}"): MerklePatriciaProofError)
                .asLeft[MerklePatriciaBatchInclusionProof]
                .pure[F],
              ifFalse = buildBatchProof(leaves.reverse)
            )
        } yield result
      }.handleError(e => ProofGenerationError(e.getMessage).asLeft[MerklePatriciaBatchInclusionProof])
    }

  object syntax {

    implicit class MerklePatriciaPrefixOps(private val prefix: Hex) extends AnyVal {

      def attestPrefixInclusion[F[_]](
        implicit P: MerklePatriciaPrefixProver[F]
      ): F[Either[MerklePatriciaProofError, MerklePatriciaBatchInclusionProof]] =
        P.attestPrefix(prefix)
    }
  }
}
