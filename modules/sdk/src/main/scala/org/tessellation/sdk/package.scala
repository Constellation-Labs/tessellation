package org.tessellation

import org.tessellation.ext.kryo._
import org.tessellation.kernel.{kernelKryoRegistrar, _}
import org.tessellation.sdk.domain.healthcheck.consensus.types.HealthCheckRoundId
import org.tessellation.sdk.infrastructure.consensus.declaration.{Facility, MajoritySignature, Proposal}
import org.tessellation.sdk.infrastructure.consensus.message._
import org.tessellation.sdk.infrastructure.consensus.trigger.{EventTrigger, TimeTrigger}
import org.tessellation.sdk.infrastructure.healthcheck.declaration._
import org.tessellation.sdk.infrastructure.healthcheck.ping._
import org.tessellation.shared.{sharedKryoRegistrar, _}

import com.comcast.ip4s._
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
      classOf[Facility] -> 507,
      classOf[Proposal] -> 508,
      classOf[MajoritySignature] -> 509,
      classOf[ConsensusArtifact[_, _]] -> 510,
      classOf[PeerDeclarationHealthCheckKey[_]] -> 511,
      classOf[PeerDeclarationConsensusHealthStatus[_]] -> 512,
      kind.Facility.getClass -> 513,
      kind.Proposal.getClass -> 514,
      kind.Signature.getClass -> 515,
      NotRequired.getClass -> 516,
      Received.getClass -> 517,
      Awaiting.getClass -> 518,
      TimedOut.getClass -> 519,
      classOf[PeerMismatch] -> 520,
      classOf[PeerCheckTimeouted] -> 521,
      classOf[PeerCheckUnexpectedError] -> 522,
      classOf[Host] -> 523,
      classOf[Port] -> 524,
      classOf[Ipv4Address] -> 525,
      classOf[Ipv6Address] -> 526,
      classOf[Hostname] -> 527,
      classOf[IDN] -> 528,
      classOf[ConsensusPeerDeclaration[_, _]] -> 529,
      EventTrigger.getClass -> 530,
      TimeTrigger.getClass -> 531
    ).union(sharedKryoRegistrar).union(kernelKryoRegistrar)
}
