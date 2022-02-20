package org.tessellation

import java.security.Signature
import java.util.UUID

import cats.data.NonEmptyList

import org.tessellation.schema.address.AddressCache
import org.tessellation.schema.gossip._
import org.tessellation.schema.height.SubHeight
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.SignRequest
import org.tessellation.schema.transaction.{Transaction, TransactionReference}
import org.tessellation.schema.trust.PublicTrust
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.SignatureProof

package object shared {

  val sharedKryoRegistrar: Map[Class[_], Int] = Map(
    classOf[UUID] -> 100,
    classOf[SignatureProof] -> 101,
    classOf[Signature] -> 102,
    classOf[SignRequest] -> 103,
    classOf[NonEmptyList[_]] -> 104,
    classOf[Signed[_]] -> 105,
    classOf[AddressCache] -> 106,
    classOf[PeerRumorBinary] -> 107,
    classOf[StartGossipRoundRequest] -> 108,
    classOf[StartGossipRoundResponse] -> 109,
    classOf[EndGossipRoundRequest] -> 110,
    classOf[EndGossipRoundResponse] -> 111,
    NodeState.Initial.getClass -> 112,
    NodeState.ReadyToJoin.getClass -> 113,
    NodeState.GenesisReady.getClass -> 114,
    NodeState.LoadingGenesis.getClass -> 115,
    NodeState.Ready.getClass -> 116,
    NodeState.SessionStarted.getClass -> 117,
    NodeState.Offline.getClass -> 118,
    NodeState.StartingSession.getClass -> 119,
    NodeState.Leaving.getClass -> 120,
    classOf[PublicTrust] -> 121,
    classOf[Ordinal] -> 122,
    classOf[CommonRumorBinary] -> 123,
    classOf[Transaction] -> 124,
    classOf[TransactionReference] -> 125,
    classOf[SubHeight] -> 126
  )

}
