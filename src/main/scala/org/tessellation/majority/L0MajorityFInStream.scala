package org.tessellation.majority

import org.tessellation.majority.SnapshotStorage.{MajorityHeight, PeersProposals, SnapshotProposalsAtHeight}
import org.tessellation.node.Peer
import org.tessellation.schema.{Hom, Ω}

sealed trait L0MajorityFInStream extends Hom[Ω, Ω]

case class PickMajorityInStream(
  peer: Peer,
  peers: Set[Peer],
  createdSnapshots: SnapshotProposalsAtHeight,
  peerProposals: PeersProposals
) extends L0MajorityFInStream
