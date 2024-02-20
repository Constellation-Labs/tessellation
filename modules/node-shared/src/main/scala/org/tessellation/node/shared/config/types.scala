package org.tessellation.node.shared.config

import cats.data.NonEmptySet

import scala.concurrent.duration.FiniteDuration

import org.tessellation.env.AppEnvironment
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.PeerId

import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt, PosLong}
import fs2.io.file.Path

object types {

  case class SharedConfigReader(
    gossip: GossipConfig,
    leavingDelay: FiniteDuration,
    stateAfterJoining: NodeState,
    collateral: Option[CollateralConfig],
    trust: SharedTrustConfig,
    snapshot: SharedSnapshotConfig,
    forkInfoStorage: ForkInfoStorageConfig,
    priorityPeerIds: Map[AppEnvironment, NonEmptySet[PeerId]],
    lastKryoHashOrdinal: Map[AppEnvironment, SnapshotOrdinal]
  )

  case class SharedConfig(
    environment: AppEnvironment,
    gossip: GossipConfig,
    http: HttpConfig,
    leavingDelay: FiniteDuration,
    stateAfterJoining: NodeState,
    collateral: CollateralConfig,
    trustStorage: TrustStorageConfig,
    priorityPeerIds: Option[NonEmptySet[PeerId]],
    snapshotSize: SnapshotSizeConfig,
    forkInfoStorage: ForkInfoStorageConfig,
    lastKryoHashOrdinal: Map[AppEnvironment, SnapshotOrdinal]
  )

  case class SharedTrustConfig(
    storage: TrustStorageConfig
  )

  case class SharedSnapshotConfig(
    size: SnapshotSizeConfig
  )

  case class SnapshotSizeConfig(
    singleSignatureSizeInBytes: PosLong,
    maxStateChannelSnapshotBinarySizeInBytes: PosLong
  )

  case class RumorStorageConfig(
    peerRumorsCapacity: PosLong,
    activeCommonRumorsCapacity: NonNegLong,
    seenCommonRumorsCapacity: NonNegLong
  )

  case class GossipDaemonConfig(
    peerRound: GossipRoundConfig,
    commonRound: GossipRoundConfig
  )

  case class GossipRoundConfig(
    fanout: PosInt,
    interval: FiniteDuration,
    maxConcurrentRounds: PosInt
  )

  case class GossipConfig(
    storage: RumorStorageConfig,
    daemon: GossipDaemonConfig
  )

  case class ConsensusConfig(
    timeTriggerInterval: FiniteDuration,
    declarationTimeout: FiniteDuration,
    declarationRangeLimit: NonNegLong,
    lockDuration: FiniteDuration,
    eventCutter: EventCutterConfig
  )

  case class EventCutterConfig(
    maxBinarySizeBytes: PosInt
  )

  case class SnapshotConfig(
    consensus: ConsensusConfig,
    inMemoryCapacity: NonNegLong,
    snapshotPath: Path,
    snapshotInfoPath: Path,
    incrementalTmpSnapshotPath: Path,
    incrementalPersistedSnapshotPath: Path
  )

  case class HttpClientConfig(
    timeout: FiniteDuration,
    idleTimeInPool: FiniteDuration
  )

  case class HttpServerConfig(
    host: Host,
    port: Port,
    shutdownTimeout: FiniteDuration
  )

  case class HttpConfig(
    externalIp: Host,
    client: HttpClientConfig,
    publicHttp: HttpServerConfig,
    p2pHttp: HttpServerConfig,
    cliHttp: HttpServerConfig
  )

  case class CollateralConfig(
    amount: Amount
  )

  case class TrustStorageConfig(
    ordinalTrustUpdateInterval: NonNegLong,
    ordinalTrustUpdateDelay: NonNegLong,
    seedlistInputBias: Double,
    seedlistOutputBias: Double
  )

  case class PeerDiscoveryDelay(
    checkPeersAttemptDelay: FiniteDuration,
    checkPeersMaxDelay: FiniteDuration,
    additionalDiscoveryDelay: FiniteDuration,
    minPeers: PosInt
  )

  case class ForkInfoStorageConfig(
    maxSize: PosInt
  )

}
