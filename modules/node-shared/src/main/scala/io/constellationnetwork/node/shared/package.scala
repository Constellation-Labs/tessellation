package io.constellationnetwork.node

import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationBlock
import io.constellationnetwork.currency.schema.globalSnapshotSync.GlobalSyncView
import io.constellationnetwork.ext.kryo._
import io.constellationnetwork.node.shared.infrastructure.consensus.CertifiedConsensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
import io.constellationnetwork.node.shared.infrastructure.consensus.message._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{EventTrigger, TimeTrigger}
import io.constellationnetwork.shared._

import com.comcast.ip4s._
import eu.timepit.refined.auto._
import eu.timepit.refined.boolean.Or
import eu.timepit.refined.numeric.Interval

package object shared {
  type NodeSharedKryoRegistrationIdRange = Interval.Closed[500, 599]

  type NodeSharedOrSharedRegistrationIdRange =
    NodeSharedKryoRegistrationIdRange Or SharedKryoRegistrationIdRange

  type NodeSharedKryoRegistrationId = KryoRegistrationId[NodeSharedKryoRegistrationIdRange]

  val nodeSharedKryoRegistrar: Map[Class[_], KryoRegistrationId[NodeSharedOrSharedRegistrationIdRange]] =
    Map[Class[_], NodeSharedKryoRegistrationId](
      // classOf[PingConsensusHealthStatus] -> 500,
      // classOf[PingHealthCheckKey] -> 501,
      // classOf[HealthCheckRoundId] -> 502,
      // classOf[PeerAvailable] -> 503,
      // classOf[PeerUnavailable] -> 504,
      // classOf[PeerUnknown] -> 505,
      classOf[ConsensusEvent[_]] -> 506,
      classOf[Facility] -> 507,
      classOf[Proposal] -> 508,
      classOf[MajoritySignature] -> 509,
      classOf[ConsensusArtifact[_, _]] -> 510,
      classOf[ProposalQC] -> 511,
      classOf[ViewChangeVote] -> 512,
      classOf[ViewChangeCertificate] -> 513,
      classOf[ConsensusPeerVote[_]] -> 514,
      // classOf[PeerMismatch] -> 520,
      // classOf[PeerCheckTimeouted] -> 521,
      // classOf[PeerCheckUnexpectedError] -> 522,
      classOf[Host] -> 523,
      classOf[Port] -> 524,
      classOf[Ipv4Address] -> 525,
      classOf[Ipv6Address] -> 526,
      classOf[Hostname] -> 527,
      classOf[IDN] -> 528,
      classOf[ConsensusPeerDeclaration[_, _]] -> 529,
      EventTrigger.getClass -> 530,
      TimeTrigger.getClass -> 531,
      classOf[DataApplicationBlock] -> 532,
      classOf[BinarySignature] -> 533,
      classOf[GlobalSyncView] -> 534, // Since the genesis snapshot is kryo encoded we need this
      // Kryo ID 535 previously registered StallReport (removed with TimeoutAggregator revert)
      // Phase B1 EvictionVote mechanism: signed negative-evidence votes, quorum-certified,
      // embedded in next Proposal to remove persistently-absent peers from the committee.
      classOf[EvictionVote] -> 535,
      classOf[EvictionCertificate] -> 536,
      classOf[ConsensusPeerEvictionVote[_]] -> 537,
      EvictionReason.Silent.getClass -> 538,
      // Phase B2 AdmissionVote mechanism: signed positive-evidence votes, quorum-certified,
      // embedded in next Proposal to re-admit previously-removed peers that have demonstrated
      // current-tip participation. Symmetric to B1 eviction.
      classOf[AdmissionVote] -> 539,
      classOf[AdmissionCertificate] -> 540,
      classOf[ConsensusPeerAdmissionVote[_]] -> 541,
      AdmissionReason.ReadyAtTip.getClass -> 542,
      classOf[TimeoutVote] -> 543,
      classOf[TimeoutCertificate] -> 544,
      classOf[ConsensusPeerTimeoutVote[_]] -> 545,
      TimeoutReason.NoProgress.getClass -> 546,
      TimeoutReason.QuorumInfeasible.getClass -> 547,
      classOf[ProposalValue] -> 548,
      classOf[CertificationStatement] -> 549,
      classOf[CertifiedProposalQC] -> 550,
      classOf[CoreCommitQC] -> 551,
      classOf[ConsensusPeerOutcomeVote[_]] -> 552,
      CertificationPurpose.Prepare.getClass -> 553,
      CertificationPurpose.Commit.getClass -> 554,
      ConsensusDomain.DagL0.getClass -> 555,
      ConsensusDomain.CurrencyL0.getClass -> 556,
      classOf[CertifiedOutcome] -> 557,
      classOf[TriggerStatement] -> 558,
      TriggerStatementPurpose.Facility.getClass -> 559
    ).union(sharedKryoRegistrar)
}
