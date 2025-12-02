package io.constellationnetwork.security.mpt.verifier

import cats.effect.Sync
import cats.syntax.all._

import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt._
import io.constellationnetwork.security.mpt.prover.attestation.MerklePatriciaRangeProof

trait MerklePatriciaRangeVerifier[F[_]] {

  def confirmRange(proof: MerklePatriciaRangeProof): F[Either[MerklePatriciaVerificationError, Unit]]
}

object MerklePatriciaRangeVerifier {
  def apply[F[_]](implicit verifier: MerklePatriciaRangeVerifier[F]): MerklePatriciaRangeVerifier[F] = verifier

  def make[F[_]: Sync: Hasher](root: Hash): MerklePatriciaRangeVerifier[F] =
    new MerklePatriciaRangeVerifier[F] {

      def confirmRange(proof: MerklePatriciaRangeProof): F[Either[MerklePatriciaVerificationError, Unit]] = {

        def hexOrdering: Ordering[Hex] = Ordering.by[Hex, String](_.value)

        def verifyInclusionProofs: F[Either[MerklePatriciaVerificationError, Unit]] = {
          val singleVerifier = MerklePatriciaInclusionVerifier.make[F](root)

          proof.inclusionProofs
            .traverse(inclusionProof => singleVerifier.confirm(inclusionProof))
            .map(results => results.sequence.map(_ => ()))
        }

        def verifyPathsInRange: F[Either[MerklePatriciaVerificationError, Unit]] = {
          val allPathsInRange = proof.inclusionProofs.forall { inclusionProof =>
            hexOrdering.compare(inclusionProof.path, proof.startPath) >= 0 &&
            hexOrdering.compare(inclusionProof.path, proof.endPath) <= 0
          }

          allPathsInRange
            .pure[F]
            .ifM(
              ifTrue = ().asRight[MerklePatriciaVerificationError].pure[F],
              ifFalse = (InvalidPath("Some paths in range proof are outside the specified range"): MerklePatriciaVerificationError)
                .asLeft[Unit]
                .pure[F]
            )
        }

        def verifyPathsOrdered: F[Either[MerklePatriciaVerificationError, Unit]] = {
          val paths = proof.inclusionProofs.map(_.path)
          val sortedPaths = paths.sorted(hexOrdering)

          (paths == sortedPaths)
            .pure[F]
            .ifM(
              ifTrue = ().asRight[MerklePatriciaVerificationError].pure[F],
              ifFalse = (InvalidWitness("Paths in range proof are not in sorted order"): MerklePatriciaVerificationError)
                .asLeft[Unit]
                .pure[F]
            )
        }

        def verifyBoundaries: F[Either[MerklePatriciaVerificationError, Unit]] =
          proof.exclusionBoundaries match {
            case Some(boundaries) =>
              val singleVerifier = MerklePatriciaInclusionVerifier.make[F](root)

              for {
                leftResult <- boundaries.leftBoundary match {
                  case Some(leftProof) =>
                    (hexOrdering.compare(leftProof.path, proof.startPath) < 0)
                      .pure[F]
                      .ifM(
                        ifTrue = singleVerifier.confirm(leftProof),
                        ifFalse = (InvalidPath(
                          s"Left boundary ${leftProof.path.value} must be < startPath ${proof.startPath.value}"
                        ): MerklePatriciaVerificationError)
                          .asLeft[Unit]
                          .pure[F]
                      )
                  case None =>
                    ().asRight[MerklePatriciaVerificationError].pure[F]
                }

                rightResult <- boundaries.rightBoundary match {
                  case Some(rightProof) =>
                    (hexOrdering.compare(rightProof.path, proof.endPath) > 0)
                      .pure[F]
                      .ifM(
                        ifTrue = singleVerifier.confirm(rightProof),
                        ifFalse = (InvalidPath(
                          s"Right boundary ${rightProof.path.value} must be > endPath ${proof.endPath.value}"
                        ): MerklePatriciaVerificationError)
                          .asLeft[Unit]
                          .pure[F]
                      )
                  case None =>
                    ().asRight[MerklePatriciaVerificationError].pure[F]
                }

                consecutiveResult <- (boundaries.leftBoundary, proof.inclusionProofs.headOption) match {
                  case (Some(left), Some(firstInclusion)) =>
                    val noGap = hexOrdering.compare(left.path, firstInclusion.path) < 0
                    noGap
                      .pure[F]
                      .ifM(
                        ifTrue = ().asRight[MerklePatriciaVerificationError].pure[F],
                        ifFalse = (InvalidWitness(s"Gap between left boundary and first inclusion"): MerklePatriciaVerificationError)
                          .asLeft[Unit]
                          .pure[F]
                      )
                  case _ =>
                    ().asRight[MerklePatriciaVerificationError].pure[F]
                }

                consecutiveRightResult <- (proof.inclusionProofs.lastOption, boundaries.rightBoundary) match {
                  case (Some(lastInclusion), Some(right)) =>
                    val noGap = hexOrdering.compare(lastInclusion.path, right.path) < 0
                    noGap
                      .pure[F]
                      .ifM(
                        ifTrue = ().asRight[MerklePatriciaVerificationError].pure[F],
                        ifFalse = (InvalidWitness(s"Gap between last inclusion and right boundary"): MerklePatriciaVerificationError)
                          .asLeft[Unit]
                          .pure[F]
                      )
                  case _ =>
                    ().asRight[MerklePatriciaVerificationError].pure[F]
                }

              } yield
                for {
                  _ <- leftResult
                  _ <- rightResult
                  _ <- consecutiveResult
                  _ <- consecutiveRightResult
                } yield ()

            case None =>
              ().asRight[MerklePatriciaVerificationError].pure[F]
          }

        (hexOrdering.compare(proof.startPath, proof.endPath) > 0)
          .pure[F]
          .ifM(
            ifTrue = (InvalidPath(
              s"Invalid range: startPath ${proof.startPath.value} > endPath ${proof.endPath.value}"
            ): MerklePatriciaVerificationError)
              .asLeft[Unit]
              .pure[F],
            ifFalse =
              for {
                inclusionResult <- verifyInclusionProofs
                rangeResult <- verifyPathsInRange
                orderResult <- verifyPathsOrdered
                boundaryResult <- verifyBoundaries
              } yield
                for {
                  _ <- inclusionResult
                  _ <- rangeResult
                  _ <- orderResult
                  _ <- boundaryResult
                } yield ()
          )
      }.handleError(e => (InvalidWitness(s"Range verification failed: ${e.getMessage}"): MerklePatriciaVerificationError).asLeft[Unit])
    }

  object syntax {

    implicit class MerklePatriciaRangeProofOps(private val proof: MerklePatriciaRangeProof) extends AnyVal {

      def confirmRange[F[_]](implicit V: MerklePatriciaRangeVerifier[F]): F[Either[MerklePatriciaVerificationError, Unit]] =
        V.confirmRange(proof)
    }
  }
}
