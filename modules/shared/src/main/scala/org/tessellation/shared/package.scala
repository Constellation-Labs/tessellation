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
    classOf[UUID] -> 300,
    classOf[SignatureProof] -> 301,
    classOf[Signature] -> 302,
    classOf[SignRequest] -> 303,
    classOf[NonEmptyList[_]] -> 304,
    classOf[Signed[_]] -> 305,
    classOf[AddressCache] -> 306,
    classOf[PeerRumorBinary] -> 307,
    classOf[StartGossipRoundRequest] -> 308,
    classOf[StartGossipRoundResponse] -> 309,
    classOf[EndGossipRoundRequest] -> 310,
    classOf[EndGossipRoundResponse] -> 311,
    NodeState.Initial.getClass -> 312,
    NodeState.ReadyToJoin.getClass -> 313,
    NodeState.GenesisReady.getClass -> 314,
    NodeState.LoadingGenesis.getClass -> 315,
    NodeState.Ready.getClass -> 316,
    NodeState.SessionStarted.getClass -> 317,
    NodeState.Offline.getClass -> 318,
    NodeState.StartingSession.getClass -> 319,
    NodeState.Leaving.getClass -> 320,
    classOf[PublicTrust] -> 321,
    classOf[Ordinal] -> 322,
    classOf[CommonRumorBinary] -> 323,
    classOf[Transaction] -> 324,
    classOf[TransactionReference] -> 325,
    classOf[SubHeight] -> 326
  )

}
