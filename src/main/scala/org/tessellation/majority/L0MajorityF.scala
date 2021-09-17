package org.tessellation.majority

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import org.tessellation.majority.SnapshotStorage.SnapshotProposal
import org.tessellation.node.Node
import org.tessellation.schema.{Hom, Ω}

sealed trait SignedSnapshotProposal extends Ω { val proposal: SnapshotProposal }
object SignedSnapshotProposal {
  implicit val codec: Codec[SignedSnapshotProposal] = deriveCodec
}

case class OwnProposal(proposal: SnapshotProposal) extends SignedSnapshotProposal

case class PeerProposal(id: String, proposal: SnapshotProposal) extends SignedSnapshotProposal

sealed trait SendProposalResponse
object SendProposalResponse {
  implicit val codec: Codec[SendProposalResponse] = deriveCodec
}

case object ProposalAccepted extends SendProposalResponse

case object ProposalRejected extends SendProposalResponse

case class Majority(value: Map[Long, String]) extends Ω

sealed trait L0MajorityF extends Hom[Ω, Ω]

case class ProcessProposal(proposal: SignedSnapshotProposal) extends L0MajorityF

case class ProposalProcessed(proposal: SignedSnapshotProposal) extends L0MajorityF

case object PickMajority extends L0MajorityF

case class L0MajorityContext(node: Node, snapshotStorage: SnapshotStorage)
