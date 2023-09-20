package org.tessellation.sdk.domain.snapshot

import scala.util.control.NoStackTrace

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.infrastructure.consensus.PeerDeclarations
import org.tessellation.security.hash.Hash

import eu.timepit.refined.types.numeric.PosDouble

trait ProposalSelect[F[_]] {

  def score(declarations: Map[PeerId, PeerDeclarations]): F[List[(Hash, PosDouble)]]

}

object ProposalSelect {

  case object NoProposals extends NoStackTrace

}
