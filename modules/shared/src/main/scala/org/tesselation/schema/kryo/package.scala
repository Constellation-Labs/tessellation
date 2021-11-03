package org.tesselation.schema
import org.tesselation.schema.address.AddressCache
import org.tesselation.schema.gossip._
import org.tesselation.schema.node.NodeState
import org.tesselation.schema.peer.SignRequest
import org.tesselation.security.signature.Signed

package object kryo {

  val schemaKryoRegistrar: Map[Class[_], Int] = Map(
    classOf[AddressCache] -> 101,
    classOf[SignRequest] -> 102,
    classOf[Signed[_]] -> 201,
    classOf[Rumor] -> 202,
    classOf[StartGossipRoundRequest] -> 203,
    classOf[StartGossipRoundResponse] -> 204,
    classOf[EndGossipRoundRequest] -> 205,
    classOf[EndGossipRoundResponse] -> 206,
    NodeState.Initial.getClass -> 207,
    NodeState.ReadyToJoin.getClass -> 208,
    NodeState.GenesisReady.getClass -> 209,
    NodeState.LoadingGenesis.getClass -> 210,
    NodeState.Ready.getClass -> 211,
    NodeState.SessionStarted.getClass -> 212,
    NodeState.Offline.getClass -> 213,
    NodeState.StartingSession.getClass -> 214,
    NodeState.Unknown.getClass -> 215
  )
}
