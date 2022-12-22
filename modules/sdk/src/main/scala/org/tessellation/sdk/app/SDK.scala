package org.tessellation.sdk.app

import java.security.KeyPair

import cats.effect.std.{Random, Supervisor}

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.generation.Generation
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.security.SecurityProvider
import org.tessellation.sdk.http.p2p.SdkP2PClient
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.sdk.modules._
import org.tessellation.sdk.resources.SdkResources

import fs2.concurrent.SignallingRef

trait SDK[F[_]] {
  implicit val random: Random[F]
  implicit val securityProvider: SecurityProvider[F]
  implicit val kryoPool: KryoSerializer[F]
  implicit val metrics: Metrics[F]
  implicit val supervisor: Supervisor[F]

  val keyPair: KeyPair
  lazy val nodeId = PeerId.fromPublic(keyPair.getPublic)
  val generation: Generation
  val seedlist: Option[Set[PeerId]]

  val sdkResources: SdkResources[F]
  val sdkP2PClient: SdkP2PClient[F]
  val sdkQueues: SdkQueues[F]
  val sdkStorages: SdkStorages[F]
  val sdkServices: SdkServices[F]
  val sdkPrograms: SdkPrograms[F]

  def restartSignal: SignallingRef[F, Unit]
}
