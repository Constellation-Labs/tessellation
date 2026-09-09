package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.Monad
import cats.syntax.all._

/** Immutable proposal-certificate input captured before a consensus state transition commits.
  *
  * A transition side effect may be replayed after cancellation or transport failure. Re-reading mutable assembled-certificate storage from
  * that replayable effect could gossip a different Proposal envelope for the already-committed CollectingProposals state. This generic
  * helper performs both reads and layer-specific selection/capping while the transition is constructed, then closes the retained effect
  * over the exact selected values.
  *
  * This is process-local transition plumbing only. It introduces no codec, hash, wire field, or consensus-state field.
  */
object ProposalCertificateEnvelope {

  final case class Captured[EvictionCertificate, AdmissionCertificate](
    evictionCertificates: List[EvictionCertificate],
    admissionCertificates: List[AdmissionCertificate]
  )

  def capture[F[_]: Monad, EvictionCertificate, AdmissionCertificate](
    loadEvictionCertificates: F[Set[EvictionCertificate]],
    selectEvictionCertificates: Set[EvictionCertificate] => F[List[EvictionCertificate]],
    loadAdmissionCertificates: F[Set[AdmissionCertificate]],
    selectAdmissionCertificates: Set[AdmissionCertificate] => F[List[AdmissionCertificate]]
  ): F[Captured[EvictionCertificate, AdmissionCertificate]] =
    for {
      assembledEvictions <- loadEvictionCertificates
      selectedEvictions <- selectEvictionCertificates(assembledEvictions)
      assembledAdmissions <- loadAdmissionCertificates
      selectedAdmissions <- selectAdmissionCertificates(assembledAdmissions)
    } yield Captured(selectedEvictions, selectedAdmissions)

  def captureRetainedEffect[F[_]: Monad, EvictionCertificate, AdmissionCertificate](
    loadEvictionCertificates: F[Set[EvictionCertificate]],
    selectEvictionCertificates: Set[EvictionCertificate] => F[List[EvictionCertificate]],
    loadAdmissionCertificates: F[Set[AdmissionCertificate]],
    selectAdmissionCertificates: Set[AdmissionCertificate] => F[List[AdmissionCertificate]]
  )(
    emit: Captured[EvictionCertificate, AdmissionCertificate] => F[Unit]
  ): F[F[Unit]] =
    capture(
      loadEvictionCertificates,
      selectEvictionCertificates,
      loadAdmissionCertificates,
      selectAdmissionCertificates
    ).flatMap(captured => Monad[F].pure(emit(captured)))

  /** Store and deliver one immutable proposal value.
    *
    * Direct gossip does not guarantee self-loopback. Self-storage first makes the leader's subsequent CollectingProposals transition
    * consume the exact envelope captured above rather than falling through to a mutable assembly-storage re-read. Both operations remain
    * replay-safe because callers close over the same proposal/declaration values.
    */
  def exactProposalEffect[F[_]: Monad, Proposal, Declaration](
    proposal: Proposal,
    declaration: Declaration
  )(
    selfStore: Proposal => F[Unit],
    deliver: Declaration => F[Unit]
  ): F[Unit] =
    selfStore(proposal) >> deliver(declaration)
}
