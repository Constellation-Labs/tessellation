package io.constellationnetwork.dag.l0.domain.snapshot.recovery

import cats.effect.Async
import cats.syntax.all._

import scala.util.control.NoStackTrace

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.{Signed, SignedValidator}
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

/** A seedlist-signed recovery anchor: an out-of-band assertion, signed by a majority of the seedlist (the allowed cluster peers), that
  * global snapshot `ordinal` has hash `snapshotHash` on the canonical chain.
  *
  * This is a SEPARATE TRUST DOMAIN from protocol finality. The recovery download path cannot reconstruct committee-relative finality (the
  * per-round committee is not available at recovery), so a recovering node cannot, on its own, distinguish the canonical chain from a
  * minority/Byzantine fork that is internally consistent and validly signed by a few peers. When a checkpoint is configured, the node
  * follows ONLY the chain that passes through this exact `(ordinal, snapshotHash)`; the checkpoint is not claiming finality, it records
  * that the trusted recovery authority (a seedlist majority) chose this fork.
  *
  * `network` binds the checkpoint to a specific network/environment so a signature cannot be replayed across networks (domain separation);
  * the dedicated type already separates it from other signed payloads.
  */
@derive(eqv, show, encoder, decoder)
case class RecoveryCheckpoint(
  network: String,
  ordinal: SnapshotOrdinal,
  snapshotHash: Hash
)

object RecoveryCheckpoint {

  /** Fork decision at a single ordinal, shared by every checkpoint enforcement site (download forward walk, already-persisted local state,
    * and observe/fetch-next) so the rule cannot drift between them.
    *
    * Returns `Some((expected, got))` when a checkpoint is configured for exactly `ordinal` and pins it to a hash other than `hash` -- i.e.
    * this chain forks from the trusted anchor at the checkpoint ordinal. Returns `None` when there is no checkpoint, the checkpoint is for
    * a different ordinal, or the hash matches.
    */
  def mismatchAt(checkpoint: Option[RecoveryCheckpoint], ordinal: SnapshotOrdinal, hash: Hash): Option[(Hash, Hash)] =
    checkpoint.collect {
      case cp if cp.ordinal === ordinal && cp.snapshotHash =!= hash => (cp.snapshotHash, hash)
    }

  sealed trait CheckpointError extends NoStackTrace {
    def message: String
    override def getMessage: String = message
  }

  case class NetworkMismatch(expected: String, got: String) extends CheckpointError {
    def message = s"recovery checkpoint network mismatch: expected '$expected', got '$got'"
  }

  case class InvalidCheckpointSignatures(reason: String) extends CheckpointError {
    def message = s"recovery checkpoint signature validation failed: $reason"
  }

  /** Verify a seedlist-signed checkpoint: bound to the expected network, all proofs cryptographically valid, every signer in the seedlist,
    * no duplicate signers, and signed by a strict majority of the seedlist (quorum intersection: two majority-signed checkpoints at the
    * same ordinal cannot disagree without a seedlist member double-signing).
    *
    * The checkpoint is off-chain, so signer and verifier must agree on the hasher; the caller supplies the current hasher.
    */
  def verify[F[_]: Async: SecurityProvider](
    signedValidator: SignedValidator[F],
    seedlist: Set[PeerId],
    expectedNetwork: String,
    signed: Signed[RecoveryCheckpoint]
  )(implicit hasher: Hasher[F]): F[Either[CheckpointError, RecoveryCheckpoint]] =
    if (signed.value.network =!= expectedNetwork)
      (NetworkMismatch(expectedNetwork, signed.value.network): CheckpointError).asLeft[RecoveryCheckpoint].pure[F]
    else
      signedValidator.validateSignatures(signed).map { cryptoValid =>
        cryptoValid
          .productL(signedValidator.validateUniqueSigners(signed))
          .productL(signedValidator.validateSignaturesWithSeedlist(seedlist.some, signed))
          .productL(signedValidator.validateSignedBySeedlistMajority(seedlist.some, signed))
          .toEither
          .leftMap(errors => InvalidCheckpointSignatures(errors.toNonEmptyList.toList.mkString(", ")): CheckpointError)
          .map(_ => signed.value)
      }
}
