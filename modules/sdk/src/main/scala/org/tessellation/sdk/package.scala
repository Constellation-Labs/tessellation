package org.tessellation

import org.tessellation.ext.kryo._
import org.tessellation.kernel.{kernelKryoRegistrar, _}
import org.tessellation.sdk.domain.healthcheck.consensus.types.HealthCheckRoundId
import org.tessellation.sdk.infrastructure.consensus.message._
import org.tessellation.sdk.infrastructure.healthcheck.declaration._
import org.tessellation.sdk.infrastructure.healthcheck.ping._
import org.tessellation.shared.{sharedKryoRegistrar, _}

import eu.timepit.refined.auto._
import eu.timepit.refined.boolean.Or
import eu.timepit.refined.numeric.Interval

package object sdk {

  type SdkKryoRegistrationIdRange = Interval.Closed[500, 599]

  type SdkOrSharedOrKernelRegistrationIdRange =
    SdkKryoRegistrationIdRange Or SharedKryoRegistrationIdRange Or KernelKryoRegistrationIdRange

  type SdkKryoRegistrationId = KryoRegistrationId[SdkKryoRegistrationIdRange]

  val sdkKryoRegistrar: Map[Class[_], KryoRegistrationId[SdkOrSharedOrKernelRegistrationIdRange]] =
    Map[Class[_], SdkKryoRegistrationId](
      classOf[PingConsensusHealthStatus] -> 500,
      classOf[PingHealthCheckKey] -> 501,
      classOf[HealthCheckRoundId] -> 502,
      classOf[PeerAvailable] -> 503,
      classOf[PeerUnavailable] -> 504,
      classOf[PeerUnknown] -> 505,
      classOf[ConsensusEvent[_]] -> 506,
      classOf[ConsensusFacility[_]] -> 507,
      classOf[ConsensusProposal[_]] -> 508,
      classOf[MajoritySignature[_]] -> 509,
      classOf[ConsensusArtifact[_, _]] -> 510,
      classOf[PeerDeclarationHealthCheckKey[_]] -> 511,
      classOf[PeerDeclarationConsensusHealthStatus[_]] -> 512,
      Facility.getClass -> 513,
      Proposal.getClass -> 514,
      Signature.getClass -> 515,
      NotRequired.getClass -> 516,
      Received.getClass -> 517,
      Awaiting.getClass -> 518,
      TimedOut.getClass -> 519,
      classOf[PeerMismatch] -> 520,
      classOf[PeerCheckTimeouted] -> 521,
      classOf[PeerCheckUnexpectedError] -> 522
    ).union(sharedKryoRegistrar).union(kernelKryoRegistrar)
}
