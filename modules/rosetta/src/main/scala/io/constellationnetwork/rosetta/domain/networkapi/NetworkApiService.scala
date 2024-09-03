package io.constellationnetwork.rosetta.domain.networkapi

import cats.data.EitherT
import cats.effect.Async
import cats.syntax.functor._

import io.constellationnetwork.BuildInfo
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.rosetta.domain.NetworkIdentifier
import io.constellationnetwork.rosetta.domain.error.{LatestSnapshotNotFound, NetworkApiError}
import io.constellationnetwork.rosetta.domain.networkapi.model.options._
import io.constellationnetwork.rosetta.domain.networkapi.model.status._
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.timestamp.SnapshotTimestamp
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, SnapshotOrdinal}
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

trait NetworkApiService[F[_]] {
  def list(appEnv: AppEnvironment): List[NetworkIdentifier]
  def options: NetworkApiOptions
  def status: EitherT[F, NetworkApiError, NetworkStatusResponse]
}

object NetworkApiService {
  private val rosettaVersion = "1.4.14"
  private val version = Version(rosettaVersion = rosettaVersion, nodeVersion = BuildInfo.version)

  type LastSnapshotInfo = (Hashed[GlobalIncrementalSnapshot], SnapshotTimestamp)

  def make[F[_]: Async](
    lastSnapshot: F[Option[LastSnapshotInfo]],
    genesisOrdinalAndHash: F[(SnapshotOrdinal, Hash)],
    nodeState: F[NodeState]
  ): NetworkApiService[F] = new NetworkApiService[F] {
    def list(appEnv: AppEnvironment): List[NetworkIdentifier] =
      NetworkIdentifier.fromAppEnvironment(appEnv).toList

    def options: NetworkApiOptions =
      NetworkApiOptions(version, Allow.default)

    def status: EitherT[F, NetworkApiError, NetworkStatusResponse] = for {
      (lastSnapshot, lastSnapshotTimestamp) <- EitherT.fromOptionF[F, NetworkApiError, LastSnapshotInfo](
        lastSnapshot,
        LatestSnapshotNotFound
      )
      (genesisOrdinal, genesisHash) <- EitherT.liftF(genesisOrdinalAndHash)
      stage <- EitherT.liftF(nodeState.map(Stage.fromNodeState))

      genesisBlockId = BlockIdentifier(genesisOrdinal, Hex(genesisHash.value))
      currentBlockId = BlockIdentifier(lastSnapshot.ordinal, Hex(lastSnapshot.hash.value))
    } yield
      NetworkStatusResponse(
        currentBlockIdentifier = currentBlockId,
        currentBlockTimestamp = lastSnapshotTimestamp.millisSinceEpoch,
        genesisBlockIdentifier = genesisBlockId,
        oldestBlockIdentifier = genesisBlockId,
        syncStatus = SyncStatus(
          currentIndex = lastSnapshot.ordinal.value.value,
          targetIndex = lastSnapshot.ordinal.value.value,
          stage = stage,
          synced = Stage.isSynced(stage)
        ),
        peers = lastSnapshot.nextFacilitators.map(RosettaPeerId(_)).toList
      )
  }
}
