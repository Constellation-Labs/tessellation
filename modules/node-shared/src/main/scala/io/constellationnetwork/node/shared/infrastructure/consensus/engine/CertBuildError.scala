package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.Eq

/** Why a `*CertificateBuilder.build` returned `Left`. The `code` projection is a stable structured-log string preserving the original
  * pre-ADT format ("under_quorum votes=N required=M", "target_mismatch peers=N", etc.), so existing operator dashboards and tests
  * grep-keyed on these prefixes keep working.
  */
sealed abstract class CertBuildError(val code: String)

object CertBuildError {
  final case class TargetMismatch(count: Int) extends CertBuildError(s"target_mismatch peers=$count")
  final case class ReasonMismatch(count: Int) extends CertBuildError(s"reason_mismatch peers=$count")
  final case class FacilitatorsHashMismatch(count: Int) extends CertBuildError(s"facilitators_mismatch peers=$count")
  final case class LastSnapshotHashMismatch(count: Int) extends CertBuildError(s"last_snapshot_hash_mismatch peers=$count")
  final case class UnderQuorum(have: Int, required: Int) extends CertBuildError(s"under_quorum votes=$have required=$required")
  case object DivergentQcs extends CertBuildError("divergent_qcs")
  case object EmptyVotesAfterFilter extends CertBuildError("empty_votes_after_filter")

  // Equality is structural via the `code` projection: two values with the same code represent the same logical rejection.
  implicit val eq: Eq[CertBuildError] = Eq.by(_.code)
}
