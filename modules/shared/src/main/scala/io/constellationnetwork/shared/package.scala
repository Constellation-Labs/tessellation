package io.constellationnetwork

import java.security.Signature

import cats.data.NonEmptyList

import io.constellationnetwork.currency.dataApplication.DataCancellationReason
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.ext.kryo._
import io.constellationnetwork.merkletree.{MerkleRoot, Proof, ProofEntry}
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.{Address, AddressCache}
import io.constellationnetwork.schema.block.Tips
import io.constellationnetwork.schema.delegatedStake.{DelegatedStakeRecord, PendingDelegatedStakeWithdrawal}
import io.constellationnetwork.schema.gossip._
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.SignRequest
import io.constellationnetwork.schema.snapshot.StateProof
import io.constellationnetwork.schema.transaction._
import io.constellationnetwork.schema.trust.PublicTrust
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.Signed.SignedOrdering
import io.constellationnetwork.security.signature.signature.SignatureProof
import io.constellationnetwork.statechannel.StateChannelSnapshotBinary

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
    classOf[Transaction] -> 329,
    Transaction.OrderingInstance.getClass -> 330,
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
    classOf[Block] -> 603,
    Block.OrderingInstance.getClass -> 604,
    classOf[BlockReference] -> 605,
    classOf[GlobalSnapshotInfoV1] -> 606,
    classOf[SnapshotTips] -> 607,
    classOf[ActiveTip] -> 608,
    ActiveTip.OrderingInstance.getClass -> 609,
    classOf[BlockAsActiveTip] -> 610,
    BlockAsActiveTip.OrderingInstance.getClass -> 611,
    classOf[DeprecatedTip] -> 612,
    DeprecatedTip.OrderingInstance.getClass -> 613,
    classOf[Tips] -> 614,
    classOf[GlobalIncrementalSnapshotV1] -> 615,
    classOf[MerkleRoot] -> 616,
    classOf[GlobalSnapshotInfoV2] -> 617,
    // classOf[CurrencyTransaction] -> 618,
    // classOf[CurrencyBlock] -> 619,
    classOf[CurrencySnapshotInfoV1] -> 620,
    classOf[CurrencySnapshot] -> 621,
    classOf[CurrencyIncrementalSnapshotV1] -> 622,
    // CurrencyBlock.OrderingInstanceAsActiveTip.getClass -> 623,
    // CurrencyTransaction.OrderingInstance.getClass -> 624,
    // CurrencyBlock.OrderingInstance.getClass -> 625,
    classOf[Proof] -> 626,
    classOf[ProofEntry] -> 627,
    classOf[GlobalSnapshotStateProofV1] -> 628,
    classOf[CurrencySnapshotStateProofV1] -> 629,
    classOf[StateProof] -> 630,
    classOf[DataApplicationPart] -> 631,
    DataCancellationReason.getClass -> 632,
    DataCancellationReason.ReceivedProposalForNonExistentOwnRound.getClass -> 633,
    DataCancellationReason.MissingRoundPeers.getClass -> 634,
    DataCancellationReason.CreatedInvalidBlock.getClass -> 635,
    DataCancellationReason.CreatedEmptyBlock.getClass -> 636,
    DataCancellationReason.PeerCancelled.getClass -> 637,
    classOf[DelegatedStakeRecord] -> 640,
    classOf[PendingDelegatedStakeWithdrawal] -> 641
  )

}
