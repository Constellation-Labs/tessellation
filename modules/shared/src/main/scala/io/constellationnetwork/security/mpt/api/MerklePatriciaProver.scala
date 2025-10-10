package io.constellationnetwork.security.mpt.api

import cats.effect.Sync
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.functor._

import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt._

trait MerklePatriciaProver[F[_]] {

  def attestPath(path: Hex): F[Either[MerklePatriciaProofError, MerklePatriciaInclusionProof]]
}

object MerklePatriciaProver {
  def apply[F[_]](implicit prover: MerklePatriciaProver[F]): MerklePatriciaProver[F] = prover

  def make[F[_]: Sync: Hasher](
    trie: MerklePatriciaTrie
  ): MerklePatriciaProver[F] =
    new MerklePatriciaProver[F] {

      def attestPath(path: Hex): F[Either[MerklePatriciaProofError, MerklePatriciaInclusionProof]] = {
        type Continue = (MerklePatriciaNode, Seq[Nibble], List[MerklePatriciaCommitment])
        type Return = Either[MerklePatriciaProofError, List[MerklePatriciaCommitment]]

        Sync[F]
          .tailRecM[Continue, Return]((trie.rootNode, Nibble(path), List.empty[MerklePatriciaCommitment])) {
            case (currentNode, remainingPath: Seq[Nibble], acc) =>
              currentNode match {
                case leaf: MerklePatriciaNode.Leaf if leaf.remaining == remainingPath =>
                  Hasher[F]
                    .hash(leaf.data)
                    .map(dataDigest => MerklePatriciaCommitment.Leaf(leaf.remaining, dataDigest) :: acc)
                    .map(commitments => commitments.asRight[MerklePatriciaProofError])
                    .map(_.asRight[Continue])
                    .handleError(e => ProofGenerationError(e.getMessage).asLeft[List[MerklePatriciaCommitment]].asRight[Continue])

                case extension: MerklePatriciaNode.Extension if remainingPath.startsWith(extension.shared) =>
                  Sync[F].pure(
                    (
                      extension.child,
                      remainingPath.drop(extension.shared.length),
                      MerklePatriciaCommitment.Extension(extension.shared, extension.child.digest) :: acc
                    ).asLeft[Return]
                  )

                case branch: MerklePatriciaNode.Branch if remainingPath.nonEmpty =>
                  branch.paths.get(remainingPath.head) match {
                    case Some(child) =>
                      Sync[F].pure(
                        (
                          child,
                          remainingPath.tail,
                          MerklePatriciaCommitment.Branch(
                            branch.paths.toSeq.sortBy(_._1.value).map { case (k, v) => k -> v.digest }.toMap
                          ) :: acc
                        ).asLeft[Return]
                      )

                    case None =>
                      Sync[F].pure(
                        PathNotFound(s"Path not found: ${path.value}")
                          .asLeft[List[MerklePatriciaCommitment]]
                          .asRight[Continue]
                      )
                  }

                case _ =>
                  Sync[F].pure(
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

      def attestInclusion[F[_]](implicit P: MerklePatriciaProver[F]): F[Either[MerklePatriciaProofError, MerklePatriciaInclusionProof]] =
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