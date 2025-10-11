package io.constellationnetwork.security.mpt.prover

import cats.effect.Sync
import cats.syntax.all._

import scala.annotation.tailrec

import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt._
import io.constellationnetwork.security.mpt.prover.attestation.MerklePatriciaBatchInclusionProof

trait MerklePatriciaBatchInclusionProver[F[_]] {

  def attestPaths(paths: List[Hex]): F[Either[MerklePatriciaProofError, MerklePatriciaBatchInclusionProof]]
}

object MerklePatriciaBatchInclusionProver {
  def apply[F[_]](implicit prover: MerklePatriciaBatchInclusionProver[F]): MerklePatriciaBatchInclusionProver[F] = prover

  def make[F[_]: Sync: Hasher](
    trie: MerklePatriciaTrie
  ): MerklePatriciaBatchInclusionProver[F] =
    new MerklePatriciaBatchInclusionProver[F] {

      def attestPaths(paths: List[Hex]): F[Either[MerklePatriciaProofError, MerklePatriciaBatchInclusionProof]] = {

        case class PathWitness(path: Hex, witness: List[MerklePatriciaCommitment])

        def attestSinglePath(path: Hex): F[Either[MerklePatriciaProofError, PathWitness]] = {
          val singleProver = MerklePatriciaSingleInclusionProver.make[F](trie)
          singleProver.attestPath(path).map(_.map(proof => PathWitness(proof.path, proof.witness)))
        }

        def deduplicateCommitments(pathWitnesses: List[PathWitness]): List[MerklePatriciaCommitment] = {
          val allCommitments = pathWitnesses.flatMap(_.witness)

          @tailrec
          def deduplicate(
            remaining: List[MerklePatriciaCommitment],
            seen: Set[String],
            acc: List[MerklePatriciaCommitment]
          ): List[MerklePatriciaCommitment] = remaining match {
            case Nil => acc.reverse
            case head :: tail =>
              val key = commitmentKey(head)
              if (seen.contains(key))
                deduplicate(tail, seen, acc)
              else
                deduplicate(tail, seen + key, head :: acc)
          }

          deduplicate(allCommitments, Set.empty, List.empty)
        }

        def commitmentKey(commitment: MerklePatriciaCommitment): String = commitment match {
          case MerklePatriciaCommitment.Leaf(remaining, dataDigest) =>
            s"leaf:${remaining.mkString}:${dataDigest.value}"
          case MerklePatriciaCommitment.Branch(pathsDigest) =>
            s"branch:${pathsDigest.toSeq.sortBy(_._1.value).map { case (k, v) => s"${k.value}:${v.value}" }.mkString(",")}"
          case MerklePatriciaCommitment.Extension(shared, childDigest) =>
            s"extension:${shared.mkString}:${childDigest.value}"
        }

        paths.isEmpty.pure[F].ifM(
          ifTrue = (ProofGenerationError("Cannot create batch proof for empty path list"): MerklePatriciaProofError)
            .asLeft[MerklePatriciaBatchInclusionProof]
            .pure[F],
          ifFalse = {
            val sortedPaths = paths.sorted(Ordering.by[Hex, String](_.value))

            sortedPaths
              .traverse(attestSinglePath)
              .map { results =>
                results.sequence.map { pathWitnesses =>
                  val deduplicated = deduplicateCommitments(pathWitnesses)
                  MerklePatriciaBatchInclusionProof(sortedPaths, deduplicated)
                }
              }
          }
        )
      }.handleError(e => ProofGenerationError(e.getMessage).asLeft[MerklePatriciaBatchInclusionProof])
    }

  object syntax {

    implicit class MerklePatriciaPathListOps(private val paths: List[Hex]) extends AnyVal {

      def attestBatchInclusion[F[_]](implicit P: MerklePatriciaBatchInclusionProver[F]): F[Either[MerklePatriciaProofError, MerklePatriciaBatchInclusionProof]] =
        P.attestPaths(paths)
    }
  }
}