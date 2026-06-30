package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.ProposalQC
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.signature.Signed

/** Shared per-signer vote aggregation for the view-change-family certificate builders
  * (`ViewChangeCertificateBuilder` and `TimeoutCertificateBuilder`).
  *
  * After each builder filters votes to its exact round payload, both perform the SAME three steps:
  * group by signer within the witness pool; detect conflicting same-view QCs over ALL of a signer's
  * votes BEFORE any collapse (so an equivocating signer's conflict cannot be silently dropped); then
  * collapse each signer to a single deterministic representative -- the highest known QC (HotStuff
  * carry-forward) with a total-`Signed` tiebreak. This was duplicated verbatim in both builders; a
  * future edit to one and not the other could reintroduce the cert divergence the determinism
  * contract exists to prevent (see ADR-0016, ADR-0020). One definition lives here.
  */
private[engine] object CertVoteAggregation {

  /** @param matching    votes already filtered to the exact (fromView, toView[, reason]) payload
    * @param witnessPool consensus-agreed set of peers whose signatures may count (caller-derived)
    * @param qcOf        extracts a vote's carried highest-known QC
    * @return (per-signer representative within the pool, whether any same-view QCs diverge)
    */
  def poolSignersAndDivergence[A](
    matching: List[Signed[A]],
    witnessPool: Set[PeerId]
  )(qcOf: A => Option[ProposalQC])(implicit ordering: Ordering[Signed[A]]): (Map[PeerId, Signed[A]], Boolean) = {
    val poolSignersAll: Map[PeerId, List[Signed[A]]] =
      matching.groupBy(_.proofs.head.id.toPeerId).filter { case (signer, _) => witnessPool.contains(signer) }
    val poolQcs = poolSignersAll.values.flatten.flatMap(s => qcOf(s.value)).toList
    val divergent = poolQcs.groupBy(_.view).exists { case (_, qcsAtView) => qcsAtView.map(_.proposalHash).toSet.size > 1 }
    val poolSigners: Map[PeerId, Signed[A]] =
      poolSignersAll.view.mapValues(_.maxBy(v => (qcOf(v.value).map(_.view).getOrElse(Long.MinValue), v))).toMap
    (poolSigners, divergent)
  }
}
