package org.tessellation.node

import org.tessellation.currency.dataApplication.dataApplication.DataApplicationBlock
import org.tessellation.ext.kryo._
import org.tessellation.node.shared.infrastructure.consensus.declaration._
import org.tessellation.node.shared.infrastructure.consensus.message._
import org.tessellation.node.shared.infrastructure.consensus.trigger.{EventTrigger, TimeTrigger}
import org.tessellation.shared._

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
      classOf[BinarySignature] -> 533
    ).union(sharedKryoRegistrar)
}
