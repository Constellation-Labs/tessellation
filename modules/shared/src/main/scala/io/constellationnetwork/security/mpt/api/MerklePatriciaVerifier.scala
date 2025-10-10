package io.constellationnetwork.security.mpt.api

import cats.effect.Sync
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.functor._

import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.mpt._

import io.circe.syntax.EncoderOps

trait MerklePatriciaVerifier[F[_]] {

  def confirm(proof: MerklePatriciaInclusionProof): F[Either[MerklePatriciaVerificationError, Unit]]
}

object MerklePatriciaVerifier {
  def apply[F[_]](implicit verifier: MerklePatriciaVerifier[F]): MerklePatriciaVerifier[F] = verifier

  def make[F[_]: Sync: Hasher](root: Hash): MerklePatriciaVerifier[F] =
    new MerklePatriciaVerifier[F] {

      def confirm(proof: MerklePatriciaInclusionProof): F[Either[MerklePatriciaVerificationError, Unit]] = {
        type Continue = (List[MerklePatriciaCommitment], Hash, Seq[Nibble])
        type Return = Either[MerklePatriciaVerificationError, Unit]

        def verifyLeaf(
          nodeCommit: MerklePatriciaCommitment.Leaf,
          currentDigest: Hash,
          remainingPath: Seq[Nibble]
        ): F[Either[Continue, Return]] =
          Hasher[F]
            .prefixedHash(nodeCommit.asJson, MerklePatriciaNode.LeafPrefix)
            .map { digest =>
              if (digest == currentDigest && remainingPath == nodeCommit.remaining)
                ().asRight[MerklePatriciaVerificationError].asRight[Continue]
              else InvalidNodeCommitment("Invalid leaf commitment or path mismatch").asLeft[Unit].asRight[Continue]
            }
            .handleError(e => InvalidNodeCommitment(s"Hash computation error: ${e.getMessage}").asLeft[Unit].asRight[Continue])

        def verifyExtension(
          nodeCommit: MerklePatriciaCommitment.Extension,
          tail: List[MerklePatriciaCommitment],
          currentDigest: Hash,
          remainingPath: Seq[Nibble]
        ): F[Either[Continue, Return]] =
          Hasher[F]
            .prefixedHash(nodeCommit.asJson, MerklePatriciaNode.ExtensionPrefix)
            .map { digest =>
              if (digest == currentDigest)
                (tail, nodeCommit.childDigest, remainingPath.drop(nodeCommit.shared.length)).asLeft[Return]
              else InvalidNodeCommitment("Invalid extension commitment").asLeft[Unit].asRight[Continue]
            }
            .handleError(e => InvalidNodeCommitment(s"Hash computation error: ${e.getMessage}").asLeft[Unit].asRight[Continue])

        def verifyBranch(
          nodeCommit: MerklePatriciaCommitment.Branch,
          tail: List[MerklePatriciaCommitment],
          currentDigest: Hash,
          remainingPath: Seq[Nibble]
        ): F[Either[Continue, Return]] =
          nodeCommit.pathsDigest.get(remainingPath.head) match {
            case Some(childDigest) =>
              Hasher[F]
                .prefixedHash(nodeCommit.asJson, MerklePatriciaNode.BranchPrefix)
                .map { digest =>
                  if (digest == currentDigest)
                    (tail, childDigest, remainingPath.tail).asLeft[Return]
                  else
                    InvalidNodeCommitment("Invalid branch commitment").asLeft[Unit].asRight[Continue]
                }
                .handleError(e => InvalidNodeCommitment(s"Hash computation error: ${e.getMessage}").asLeft[Unit].asRight[Continue])

            case None =>
              Sync[F].pure(
                InvalidPath(s"Path not found in branch: ${remainingPath.head}").asLeft[Unit].asRight[Continue]
              )
          }

        Sync[F]
          .tailRecM[Continue, Return]((proof.witness.reverse, root, Nibble(proof.path))) {
            case (commitments, currentDigest, remainingPath) =>
              commitments match {
                case (nodeCommit: MerklePatriciaCommitment.Leaf) :: Nil =>
                  verifyLeaf(nodeCommit, currentDigest, remainingPath)

                case (nodeCommit: MerklePatriciaCommitment.Extension) :: tail =>
                  verifyExtension(nodeCommit, tail, currentDigest, remainingPath)

                case (nodeCommit: MerklePatriciaCommitment.Branch) :: tail =>
                  verifyBranch(nodeCommit, tail, currentDigest, remainingPath)

                case _ =>
                  Sync[F].pure(
                    InvalidWitness("Invalid witness structure").asLeft[Unit].asRight[Continue]
                  )
              }
          }
          .handleError(e => InvalidWitness(s"Verification failed with error: ${e.getMessage}").asLeft[Unit])
      }
    }

  object syntax {

    implicit class MerklePatriciaProofOps(private val proof: MerklePatriciaInclusionProof) extends AnyVal {

      def confirm[F[_]](implicit V: MerklePatriciaVerifier[F]): F[Either[MerklePatriciaVerificationError, Unit]] =
        V.confirm(proof)
    }
  }
}

sealed trait MerklePatriciaVerificationError extends Throwable

case class InvalidWitness(message: String) extends MerklePatriciaVerificationError {
  override def getMessage: String = message
}

case class InvalidPath(message: String) extends MerklePatriciaVerificationError {
  override def getMessage: String = message
}

case class InvalidNodeCommitment(message: String) extends MerklePatriciaVerificationError {
  override def getMessage: String = message
}