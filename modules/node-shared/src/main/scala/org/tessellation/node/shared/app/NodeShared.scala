package org.tessellation.node.shared.app

import java.security.KeyPair

import cats.effect.std.{Random, Supervisor}

import org.tessellation.json.JsonSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.domain.seedlist.SeedlistEntry
import org.tessellation.node.shared.http.p2p.SharedP2PClient
import org.tessellation.node.shared.infrastructure.metrics.Metrics
import org.tessellation.node.shared.modules._
import org.tessellation.node.shared.resources.SharedResources
import org.tessellation.schema.generation.Generation
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.trust.PeerObservationAdjustmentUpdateBatch
import org.tessellation.security.{HashSelect, Hasher, SecurityProvider}

import fs2.concurrent.SignallingRef

trait NodeShared[F[_]] {
  implicit val random: Random[F]
  implicit val securityProvider: SecurityProvider[F]
  implicit val kryoPool: KryoSerializer[F]
  implicit val jsonSerializer: JsonSerializer[F]
  implicit val metrics: Metrics[F]
  implicit val supervisor: Supervisor[F]
  implicit val hasher: Hasher[F]

  val keyPair: KeyPair
  lazy val nodeId: PeerId = PeerId.fromPublic(keyPair.getPublic)
  val generation: Generation
  val seedlist: Option[Set[SeedlistEntry]]
  val trustRatings: Option[PeerObservationAdjustmentUpdateBatch]

  val sharedResources: SharedResources[F]
  val sharedP2PClient: SharedP2PClient[F]
  val sharedQueues: SharedQueues[F]
  val sharedStorages: SharedStorages[F]
  val sharedServices: SharedServices[F]
  val sharedPrograms: SharedPrograms[F]
  val sharedValidators: SharedValidators[F]
  val prioritySeedlist: Option[Set[SeedlistEntry]]

  val hashSelect: HashSelect

  def restartSignal: SignallingRef[F, Unit]
  def stopSignal: SignallingRef[F, Boolean]
}
