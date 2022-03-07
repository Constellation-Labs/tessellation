package org.tessellation

import java.security.Signature
import java.util.UUID

import cats.data.NonEmptyList

import org.tessellation.ext.kryo._
import org.tessellation.schema.address.AddressCache
import org.tessellation.schema.gossip._
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.SignRequest
import org.tessellation.schema.transaction.{Transaction, TransactionReference}
import org.tessellation.schema.trust.PublicTrust
import org.tessellation.security.signature.Signed
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
    NodeState.LoadingGenesis.getClass -> 314,
    NodeState.GenesisReady.getClass -> 315,
    NodeState.StartingSession.getClass -> 316,
    NodeState.SessionStarted.getClass -> 317,
    NodeState.WaitingForDownload.getClass -> 318,
    NodeState.DownloadInProgress.getClass -> 319,
    NodeState.Ready.getClass -> 320,
    NodeState.Leaving.getClass -> 321,
    NodeState.Offline.getClass -> 322,
    classOf[PublicTrust] -> 323,
    classOf[Ordinal] -> 324,
    classOf[CommonRumorBinary] -> 325,
    classOf[Transaction] -> 326,
    classOf[TransactionReference] -> 327,
    classOf[Refined[_, _]] -> 328
  )

}
