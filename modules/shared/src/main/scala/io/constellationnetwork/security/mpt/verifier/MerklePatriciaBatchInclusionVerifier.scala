package io.constellationnetwork.security.mpt.verifier

import cats.effect.Sync
import cats.syntax.all._

import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt._
import io.constellationnetwork.security.mpt.prover.attestation.{MerklePatriciaBatchInclusionProof, MerklePatriciaInclusionProof}

import io.circe.syntax._

trait MerklePatriciaBatchInclusionVerifier[F[_]] {

  def confirm(proof: MerklePatriciaBatchInclusionProof): F[Either[MerklePatriciaVerificationError, Unit]]
}

object MerklePatriciaBatchInclusionVerifier {
  def apply[F[_]](implicit verifier: MerklePatriciaBatchInclusionVerifier[F]): MerklePatriciaBatchInclusionVerifier[F] = verifier

  def make[F[_]: Sync: Hasher](root: Hash): MerklePatriciaBatchInclusionVerifier[F] =
    new MerklePatriciaBatchInclusionVerifier[F] {

      def confirm(proof: MerklePatriciaBatchInclusionProof): F[Either[MerklePatriciaVerificationError, Unit]] = {

        def reconstructProof(path: Hex, sharedWitness: List[MerklePatriciaCommitment]): F[Either[MerklePatriciaVerificationError, Unit]] = {
          type Continue = (Seq[Nibble], Hash, List[MerklePatriciaCommitment])
          type Return = Either[MerklePatriciaVerificationError, List[MerklePatriciaCommitment]]

          val pathNibbles = Nibble(path)

          def findMatchingCommitment(
            expectedDigest: Hash,
            remainingPath: Seq[Nibble],
            witnesses: List[MerklePatriciaCommitment]
          ): F[Option[(MerklePatriciaCommitment, Hash, Seq[Nibble])]] = {

            def checkCommitment(commitment: MerklePatriciaCommitment): F[Option[(MerklePatriciaCommitment, Hash, Seq[Nibble])]] =
              commitment match {
                case leaf: MerklePatriciaCommitment.Leaf =>
                  Hasher[F]
                    .prefixedHash(leaf.asJson, MerklePatriciaNode.LeafPrefix)
                    .map { digest =>
                      if (digest == expectedDigest && remainingPath == leaf.remaining)
                        Some((leaf, expectedDigest, Seq.empty))
                      else
                        None
                    }

                case ext: MerklePatriciaCommitment.Extension =>
                  Hasher[F]
                    .prefixedHash(ext.asJson, MerklePatriciaNode.ExtensionPrefix)
                    .map { digest =>
                      if (digest == expectedDigest && remainingPath.startsWith(ext.shared))
                        Some((ext, ext.childDigest, remainingPath.drop(ext.shared.length)))
                      else
                        None
                    }

                case branch: MerklePatriciaCommitment.Branch =>
                  Hasher[F]
                    .prefixedHash(branch.asJson, MerklePatriciaNode.BranchPrefix)
                    .map { digest =>
                      if (digest == expectedDigest && remainingPath.nonEmpty && branch.pathsDigest.contains(remainingPath.head))
                        Some((branch, branch.pathsDigest(remainingPath.head), remainingPath.tail))
                      else
                        None
                    }
              }

            witnesses
              .traverse(checkCommitment)
              .map(_.collectFirst { case Some(result) => result })
          }

          Sync[F]
            .tailRecM[Continue, Return]((pathNibbles, root, List.empty[MerklePatriciaCommitment])) {
              case (remainingPath, expectedDigest, acc) =>
                if (remainingPath.isEmpty)
                  Sync[F].pure(acc.asRight[MerklePatriciaVerificationError].asRight[Continue])
                else
                  findMatchingCommitment(expectedDigest, remainingPath, sharedWitness).flatMap {
                    case Some((commitment, nextDigest, nextPath)) =>
                      commitment match {
                        case _: MerklePatriciaCommitment.Leaf =>
                          Sync[F].pure((commitment :: acc).asRight[MerklePatriciaVerificationError].asRight[Continue])
                        case _ =>
                          Sync[F].pure((nextPath, nextDigest, commitment :: acc).asLeft[Return])
                      }

                    case None =>
                      Sync[F].pure(
                        InvalidWitness(
                          s"No matching commitment found for digest ${expectedDigest.value} at path ${path.value} (position ${pathNibbles.length - remainingPath.length}/${pathNibbles.length})"
                        )
                          .asLeft[List[MerklePatriciaCommitment]]
                          .asRight[Continue]
                      )
                  }
            }
            .flatMap {
              case Right(relevantCommitments) =>
                MerklePatriciaInclusionVerifier
                  .make[F](root)
                  .confirm(
                    MerklePatriciaInclusionProof(
                      path,
                      relevantCommitments
                    )
                  )
              case Left(error) =>
                Sync[F].pure(error.asLeft[Unit])
            }
        }

        proof.paths.isEmpty
          .pure[F]
          .ifM(
            ifTrue = (InvalidWitness("Batch proof cannot have empty paths list"): MerklePatriciaVerificationError)
              .asLeft[Unit]
              .pure[F],
            ifFalse = proof.paths
              .traverse(path => reconstructProof(path, proof.witness))
              .map { results =>
                results.sequence.map(_ => ())
              }
          )
      }.handleError(e => InvalidWitness(s"Batch verification failed: ${e.getMessage}").asLeft[Unit])
    }

  object syntax {

    implicit class MerklePatriciaBatchInclusionProofOps(private val proof: MerklePatriciaBatchInclusionProof) extends AnyVal {

      def confirm[F[_]](implicit V: MerklePatriciaBatchInclusionVerifier[F]): F[Either[MerklePatriciaVerificationError, Unit]] =
        V.confirm(proof)
    }
  }
}
