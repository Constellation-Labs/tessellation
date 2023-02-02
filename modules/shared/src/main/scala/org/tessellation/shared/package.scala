package org.tessellation

import java.security.Signature

import cats.data.NonEmptyList

import org.tessellation.ext.kryo._
import org.tessellation.schema._
import org.tessellation.schema.address.{Address, AddressCache}
import org.tessellation.schema.block.{DAGBlock, Tips}
import org.tessellation.schema.gossip._
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.SignRequest
import org.tessellation.schema.transaction.{DAGTransaction, RewardTransaction, TransactionReference}
import org.tessellation.schema.trust.PublicTrust
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.Signed.SignedOrdering
import org.tessellation.security.signature.signature.SignatureProof
import org.tessellation.statechannel.StateChannelSnapshotBinary

import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Greater

package object shared {

  type SharedKryoRegistrationIdRange = Greater[100]

  type SharedKryoRegistrationId = KryoRegistrationId[SharedKryoRegistrationIdRange]

  val sharedKryoRegistrar: Map[Class[_], SharedKryoRegistrationId] = Map(
    classOf[SignatureProof] -> 301,
    SignatureProof.OrderingInstance.getClass -> 302,
    classOf[Signature] -> 303,
    classOf[SignRequest] -> 304,
    classOf[NonEmptyList[_]] -> 305,
    classOf[Signed[_]] -> 306,
    classOf[AddressCache] -> 307,
    classOf[PeerRumorRaw] -> 308,
    NodeState.Initial.getClass -> 313,
    NodeState.ReadyToJoin.getClass -> 314,
    NodeState.LoadingGenesis.getClass -> 315,
    NodeState.GenesisReady.getClass -> 316,
    NodeState.RollbackInProgress.getClass -> 317,
    NodeState.RollbackDone.getClass -> 318,
    NodeState.StartingSession.getClass -> 319,
    NodeState.SessionStarted.getClass -> 320,
    NodeState.WaitingForDownload.getClass -> 321,
    NodeState.DownloadInProgress.getClass -> 322,
    NodeState.Ready.getClass -> 323,
    NodeState.Leaving.getClass -> 324,
    NodeState.Offline.getClass -> 325,
    classOf[PublicTrust] -> 326,
    classOf[Ordinal] -> 327,
    classOf[CommonRumorRaw] -> 328,
    classOf[DAGTransaction] -> 329,
    DAGTransaction.OrderingInstance.getClass -> 330,
    classOf[TransactionReference] -> 331,
    classOf[Refined[_, _]] -> 332,
    classOf[RewardTransaction] -> 333,
    RewardTransaction.OrderingInstance.getClass -> 334,
    classOf[SignedOrdering[_]] -> 335,
    Address.OrderingInstance.getClass -> 336,
    NodeState.Observing.getClass -> 337,
    classOf[GlobalSnapshot] -> 600,
    classOf[StateChannelSnapshotBinary] -> 601,
    classOf[SnapshotOrdinal] -> 602,
    classOf[DAGBlock] -> 603,
    DAGBlock.OrderingInstance.getClass -> 604,
    classOf[BlockReference] -> 605,
    classOf[GlobalSnapshotInfo] -> 606,
    classOf[SnapshotTips] -> 607,
    classOf[ActiveTip] -> 608,
    ActiveTip.OrderingInstance.getClass -> 609,
    classOf[BlockAsActiveTip[DAGBlock]] -> 610,
    DAGBlock.OrderingInstanceAsActiveTip.getClass -> 611,
    classOf[DeprecatedTip] -> 612,
    DeprecatedTip.OrderingInstance.getClass -> 613,
    classOf[Tips] -> 614,
    classOf[IncrementalGlobalSnapshot] -> 615
  )

}
