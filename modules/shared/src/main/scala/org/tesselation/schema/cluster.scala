package org.tesselation.schema

import scala.util.control.NoStackTrace

import org.tesselation.schema.node.NodeState
import org.tesselation.schema.peer.PeerId

import com.comcast.ip4s.{Host, Port}
import derevo.cats.show
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

object cluster {

  @derive(decoder, encoder, show)
  case class PeerToJoin(id: PeerId, ip: Host, p2pPort: Port)

  case class NodeStateDoesNotAllowForJoining(nodeState: NodeState) extends NoStackTrace
  case class PeerIdInUse(id: PeerId) extends NoStackTrace
  case class PeerHostPortInUse(host: Host, p2pPort: Port) extends NoStackTrace

}
