package io.constellationnetwork.security.mpt.prover

import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt._
import io.constellationnetwork.security.mpt.prover.attestation.MerklePatriciaInclusionProof

import io.circe.syntax._

trait MerklePatriciaSingleInclusionProver[F[_]] {

  def attestPath(path: Hex): F[Either[MerklePatriciaProofError, MerklePatriciaInclusionProof]]
}

object MerklePatriciaSingleInclusionProver {
  def apply[F[_]](implicit prover: MerklePatriciaSingleInclusionProver[F]): MerklePatriciaSingleInclusionProver[F] = prover

  def make[F[_]: Async: Hasher](
    trie: MerklePatriciaTrie
  ): MerklePatriciaSingleInclusionProver[F] =
    new MerklePatriciaSingleInclusionProver[F] {

      private def computeNodeHash(node: MerklePatriciaNode): F[Hash] =
        node match {
          case leaf: MerklePatriciaNode.Leaf =>
            val commitment = MerklePatriciaCommitment.Leaf(leaf.remaining, leaf.dataDigest)
            Hasher[F].prefixedHash(commitment.asJson, MerklePatriciaNode.LeafPrefix)
          case branch: MerklePatriciaNode.Branch =>
            computeBranchHash(branch)
          case ext: MerklePatriciaNode.Extension =>
            computeExtensionHash(ext)
        }

      private def computeBranchHash(branch: MerklePatriciaNode.Branch): F[Hash] =
        for {
          pathDigests <- branch.paths.toList.traverse {
            case (nibble, child) =>
              computeNodeHash(child).map(nibble -> _)
          }.map(_.toMap)
          commitment = MerklePatriciaCommitment.Branch(pathDigests)
          hash <- Hasher[F].prefixedHash(commitment.asJson, MerklePatriciaNode.BranchPrefix)
        } yield hash

      private def computeExtensionHash(ext: MerklePatriciaNode.Extension): F[Hash] =
        for {
          childHash <- computeBranchHash(ext.child)
          commitment = MerklePatriciaCommitment.Extension(ext.shared, childHash)
          hash <- Hasher[F].prefixedHash(commitment.asJson, MerklePatriciaNode.ExtensionPrefix)
        } yield hash

      def attestPath(path: Hex): F[Either[MerklePatriciaProofError, MerklePatriciaInclusionProof]] = {
        type Continue = (MerklePatriciaNode, Seq[Nibble], List[MerklePatriciaCommitment])
        type Return = Either[MerklePatriciaProofError, List[MerklePatriciaCommitment]]

        Async[F]
          .tailRecM[Continue, Return]((trie.rootNode, Nibble(path), List.empty[MerklePatriciaCommitment])) {
            case (currentNode, remainingPath: Seq[Nibble], acc) =>
              currentNode match {
                case leaf: MerklePatriciaNode.Leaf if leaf.remaining == remainingPath =>
                  val commitment = MerklePatriciaCommitment.Leaf(leaf.remaining, leaf.dataDigest)
                  Async[F].pure(
                    (commitment :: acc)
                      .asRight[MerklePatriciaProofError]
                      .asRight[Continue]
                  )

                case extension: MerklePatriciaNode.Extension if remainingPath.startsWith(extension.shared) =>
                  computeBranchHash(extension.child).map { childDigest =>
                    (
                      extension.child,
                      remainingPath.drop(extension.shared.length),
                      MerklePatriciaCommitment.Extension(extension.shared, childDigest) :: acc
                    ).asLeft[Return]
                  }

                case branch: MerklePatriciaNode.Branch if remainingPath.nonEmpty =>
                  branch.paths.get(remainingPath.head) match {
                    case Some(child) =>
                      branch.paths.toList.traverse {
                        case (k, v) =>
                          computeNodeHash(v).map(k -> _)
                      }.map { pathDigests =>
                        (
                          child,
                          remainingPath.tail,
                          MerklePatriciaCommitment.Branch(pathDigests.toMap) :: acc
                        ).asLeft[Return]
                      }

                    case None =>
                      Async[F].pure(
                        PathNotFound(s"Path not found: ${path.value}")
                          .asLeft[List[MerklePatriciaCommitment]]
                          .asRight[Continue]
                      )
                  }

                case _ =>
                  Async[F].pure(
                    InvalidNodeType(s"Unexpected node type encountered for path: ${path.value}")
                      .asLeft[List[MerklePatriciaCommitment]]
                      .asRight[Continue]
                  )
              }
          }
          .map(_.map(commitments => MerklePatriciaInclusionProof(path, commitments)))
          .handleError(e => ProofGenerationError(e.getMessage).asLeft[MerklePatriciaInclusionProof])
      }
    }

  object syntax {

    implicit class MerklePatriciaPathOps(private val path: Hex) extends AnyVal {

      def attestInclusion[F[_]](
        implicit P: MerklePatriciaSingleInclusionProver[F]
      ): F[Either[MerklePatriciaProofError, MerklePatriciaInclusionProof]] =
        P.attestPath(path)
    }
  }
}

sealed trait MerklePatriciaProofError extends Throwable

case class PathNotFound(path: String) extends MerklePatriciaProofError {
  override def getMessage: String = s"Path not found: $path"
}

case class InvalidNodeType(message: String) extends MerklePatriciaProofError {
  override def getMessage: String = message
}

case class ProofGenerationError(message: String) extends MerklePatriciaProofError {
  override def getMessage: String = message
}
