package org.tessellation

import java.security.Signature
import java.util.UUID

import cats.data.NonEmptyList

import org.tessellation.ext.kryo._
import org.tessellation.schema.address.{Address, AddressCache}
import org.tessellation.schema.gossip._
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.SignRequest
import org.tessellation.schema.transaction.{RewardTransaction, Transaction, TransactionReference}
import org.tessellation.schema.trust.PublicTrust
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.Signed.SignedOrdering
import org.tessellation.security.signature.signature.SignatureProof

import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Interval

package object shared {

  type SharedKryoRegistrationIdRange = Interval.Closed[300, 399]

  type SharedKryoRegistrationId = KryoRegistrationId[SharedKryoRegistrationIdRange]

  val sharedKryoRegistrar: Map[Class[_], SharedKryoRegistrationId] = Map(
    classOf[UUID] -> 300,
    classOf[SignatureProof] -> 301,
    SignatureProof.OrderingInstance.getClass -> 302,
    classOf[Signature] -> 303,
    classOf[SignRequest] -> 304,
    classOf[NonEmptyList[_]] -> 305,
    classOf[Signed[_]] -> 306,
    classOf[AddressCache] -> 307,
    classOf[PeerRumorBinary] -> 308,
    classOf[StartGossipRoundRequest] -> 309,
    classOf[StartGossipRoundResponse] -> 310,
    classOf[EndGossipRoundRequest] -> 311,
    classOf[EndGossipRoundResponse] -> 312,
    NodeState.Initial.getClass -> 313,
    NodeState.ReadyToJoin.getClass -> 314,
    NodeState.LoadingGenesis.getClass -> 315,
    NodeState.GenesisReady.getClass -> 316,
    NodeState.StartingSession.getClass -> 317,
    NodeState.SessionStarted.getClass -> 318,
    NodeState.WaitingForDownload.getClass -> 319,
    NodeState.DownloadInProgress.getClass -> 320,
    NodeState.Ready.getClass -> 321,
    NodeState.Leaving.getClass -> 322,
    NodeState.Offline.getClass -> 323,
    classOf[PublicTrust] -> 324,
    classOf[Ordinal] -> 325,
    classOf[CommonRumorBinary] -> 326,
    classOf[Transaction] -> 327,
    Transaction.OrderingInstance.getClass -> 328,
    classOf[TransactionReference] -> 329,
    classOf[Refined[_, _]] -> 330,
    classOf[RewardTransaction] -> 331,
    RewardTransaction.OrderingInstance.getClass -> 332,
    classOf[SignedOrdering[_]] -> 333,
    Address.OrderingInstance.getClass -> 334
  )

}
