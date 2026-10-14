package io.constellationnetwork.node.shared.infrastructure.consensus.state

/** The chain leaves bootstrap once a recent round's canonical committee (round-start committee minus certified evictions) reached
  * `MinimumSize`. The signal is the consensus-agreed `recentCommitteeSizes` window, never a locally observed proof count.
  */
object BootstrapCommittee {

  val MinimumSize: Int = 3

  def reached(recentCommitteeSizes: Iterable[Int]): Boolean =
    recentCommitteeSizes.exists(_ >= MinimumSize)
}
