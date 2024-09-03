package io.constellationnetwork.node.shared.app

import java.security.KeyPair

import cats.effect.std.{Random, Supervisor}

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.config.types.SharedConfig
import io.constellationnetwork.node.shared.domain.seedlist.SeedlistEntry
import io.constellationnetwork.node.shared.http.p2p.SharedP2PClient
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.modules._
import io.constellationnetwork.node.shared.resources.SharedResources
import io.constellationnetwork.schema.generation.Generation
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.trust.PeerObservationAdjustmentUpdateBatch
import io.constellationnetwork.security.{HashSelect, HasherSelector, SecurityProvider}

import fs2.concurrent.SignallingRef

trait NodeShared[F[_]] {
  implicit val random: Random[F]
  implicit val securityProvider: SecurityProvider[F]
  implicit val kryoPool: KryoSerializer[F]
  implicit val jsonSerializer: JsonSerializer[F]
  implicit val metrics: Metrics[F]
  implicit val supervisor: Supervisor[F]
  implicit val hasherSelector: HasherSelector[F]

  val keyPair: KeyPair
  lazy val nodeId: PeerId = PeerId.fromPublic(keyPair.getPublic)
  val generation: Generation
  val seedlist: Option[Set[SeedlistEntry]]
  val trustRatings: Option[PeerObservationAdjustmentUpdateBatch]

  val sharedConfig: SharedConfig

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
