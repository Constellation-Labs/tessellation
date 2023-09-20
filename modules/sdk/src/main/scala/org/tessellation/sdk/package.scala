package org.tessellation

import cats.data.NonEmptySet
import cats.syntax.option._

import org.tessellation.cli.AppEnvironment
import org.tessellation.ext.kryo._
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.healthcheck.consensus.types.HealthCheckRoundId
import org.tessellation.sdk.infrastructure.consensus.declaration.{Facility, MajoritySignature, Proposal}
import org.tessellation.sdk.infrastructure.consensus.message._
import org.tessellation.sdk.infrastructure.consensus.trigger.{EventTrigger, TimeTrigger}
import org.tessellation.sdk.infrastructure.healthcheck.ping._
import org.tessellation.security.hex.Hex
import org.tessellation.shared._

import com.comcast.ip4s._
import eu.timepit.refined.auto._
import eu.timepit.refined.boolean.Or
import eu.timepit.refined.numeric.Interval

import AppEnvironment._

package object sdk {
  type SdkKryoRegistrationIdRange = Interval.Closed[500, 599]

  type SdkOrSharedOrKernelRegistrationIdRange =
    SdkKryoRegistrationIdRange Or SharedKryoRegistrationIdRange

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
    ).union(sharedKryoRegistrar)

  object PriorityPeerIds {
    private val mainnet = NonEmptySet
      .of(
        "e0c1ee6ec43510f0e16d2969a7a7c074a5c8cdb477c074fe9c32a9aad8cbc8ff1dff60bb81923e0db437d2686a9b65b86c403e6a21fa32b6acc4e61be4d70925",
        "710b3dc521b805aea7a798d61f5d4dae39601124f1f34fac9738a78047adeff60931ba522250226b87a2194d3b7d39da8d2cbffa35d6502c70f1a7e97132a4b0",
        "629880a5b8d4cc6d12aec26f24230a463825c429723153aeaff29475b29e39d2406af0f8b034ba7798ae598dbd5f513d642bcbbeef088290abeadac61a0445d6"
      )
      .map(s => PeerId(Hex(s)))

    private val integrationnet = NonEmptySet
      .of(
        "e2f4496e5872682d7a55aa06e507a58e96b5d48a5286bfdff7ed780fa464d9e789b2760ecd840f4cb3ee6e1c1d81b2ee844c88dbebf149b1084b7313eb680714",
        "3458a688925a4bd89f2ac2c695362e44d2e0c2903bdbb41b341a4d39283b22d8c85b487bd33cc5d36dbe5e31b5b00a10a6eab802718ead4ed7192ade5a5d1941",
        "46daea11ca239cb8c0c8cdeb27db9dbe9c03744908a8a389a60d14df2ddde409260a93334d74957331eec1af323f458b12b3a6c3b8e05885608aae7e3a77eac7"
      )
      .map(s => PeerId(Hex(s)))

    private val testnet = NonEmptySet
      .of(
        "e2f4496e5872682d7a55aa06e507a58e96b5d48a5286bfdff7ed780fa464d9e789b2760ecd840f4cb3ee6e1c1d81b2ee844c88dbebf149b1084b7313eb680714",
        "3458a688925a4bd89f2ac2c695362e44d2e0c2903bdbb41b341a4d39283b22d8c85b487bd33cc5d36dbe5e31b5b00a10a6eab802718ead4ed7192ade5a5d1941",
        "46daea11ca239cb8c0c8cdeb27db9dbe9c03744908a8a389a60d14df2ddde409260a93334d74957331eec1af323f458b12b3a6c3b8e05885608aae7e3a77eac7"
      )
      .map(s => PeerId(Hex(s)))

    def get(env: AppEnvironment): Option[NonEmptySet[PeerId]] = env match {
      case Integrationnet => integrationnet.some
      case Testnet        => testnet.some
      case Mainnet        => mainnet.some
      case Dev            => none
    }
  }
}
