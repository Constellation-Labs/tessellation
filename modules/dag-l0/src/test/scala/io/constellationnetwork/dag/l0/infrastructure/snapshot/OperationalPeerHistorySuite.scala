package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.effect.{IO, Resource}

import scala.collection.immutable.SortedMap

import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema.{Finished, GlobalConsensusOutcome}
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.parser.decode
import io.circe.syntax._
import weaver.MutableIOSuite

/** Operational peerHistory plumbing on the real `GlobalConsensusOutcome`.
  *
  *   - Round trip: outcome -> `toOperationalState` -> circe encode/decode (what the peerHistory sidecar does) preserves penalties,
  *     probation, round-end times, and penalty horizons across a cold restart.
  *   - Signed bytes: two outcomes that agree on the chain-derived windows but carry different node-local per-peer bookkeeping produce
  *     byte-identical `signedArtifactPeerHistory`, which never carries per-peer records or round-end times.
  */
object OperationalPeerHistorySuite extends MutableIOSuite {
  implicit val globalStateProofSelector: GlobalStateProofSelector = GlobalStateProofSelector(SnapshotOrdinal(NonNegLong(Long.MaxValue)))

  type Res = (JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  def sharedResource: Resource[IO, Res] = for {
    implicit0(sp: SecurityProvider[IO]) <- SecurityProvider.forAsync[IO]
    implicit0(js: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    h = Hasher.forJson[IO]
  } yield (js, h, sp)

  private def peer(c: Char): PeerId = PeerId(Hex(c.toString * 128))
  private def ord(n: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(n)

  private val a = peer('a')
  private val b = peer('b')
  private val c = peer('c')

  private val penaltyUntil: SortedMap[PeerId, SnapshotOrdinal] = SortedMap(c -> ord(120L))
  private val recentProofSizes: SortedMap[SnapshotOrdinal, Int] = SortedMap(ord(13L) -> 3, ord(14L) -> 3)

  private def mkFinished(implicit sp: SecurityProvider[IO], h: Hasher[IO], js: JsonSerializer[IO]): IO[Finished] =
    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      genesis = GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)
      signedGenesis <- Signed.forAsyncHasher[IO, GlobalSnapshot](genesis, keyPair)
      lastArtifact <- GlobalIncrementalSnapshot.fromGlobalSnapshot[IO](signedGenesis.value)
      signedLastArtifact <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](lastArtifact, keyPair)
      snapshotHash <- signedLastArtifact.toHashed.map(_.hash)
    } yield Finished(signedLastArtifact, signedGenesis.value.info.toGlobalSnapshotInfo, EventTrigger, Candidates.empty, Hash.empty, snapshotHash)

  private def mkOutcome(
    finished: Finished,
    removalPenalties: SortedMap[PeerId, Int],
    readmissionCountdown: SortedMap[PeerId, Int],
    recentRoundEndTimes: SortedMap[SnapshotOrdinal, Long]
  ): GlobalConsensusOutcome =
    GlobalConsensusOutcome(
      key = ord(14L),
      facilitators = Facilitators(List(a, b, c)),
      removedFacilitators = RemovedFacilitators.empty,
      withdrawnFacilitators = WithdrawnFacilitators.empty,
      eligibleFacilitators = EligibleFacilitators(List(a, b, c)),
      finished = finished,
      removalPenalties = removalPenalties,
      recentProofSizes = recentProofSizes,
      readmissionCountdown = readmissionCountdown,
      recentRoundEndTimes = recentRoundEndTimes,
      penaltyUntil = Some(penaltyUntil)
    )

  test("operational state survives the sidecar encode/decode round trip") { res =>
    implicit val (js, h, sp) = res
    for {
      finished <- mkFinished
      outcome = mkOutcome(finished, SortedMap(c -> 2), SortedMap(b -> 3), SortedMap(ord(14L) -> 1700000000000L))
      operational = outcome.toOperationalState
      decoded = decode[ConsensusOperationalState](operational.asJson.noSpaces)
    } yield expect(decoded.contains(operational), s"round trip changed the operational state: $decoded") &&
      expect.same(Some(penaltyUntil), decoded.toOption.flatMap(_.penaltyUntil)) &&
      expect.same(Some(3), decoded.toOption.flatMap(_.perPeer.get(b)).map(_.readmissionCountdown)) &&
      expect.same(Some(2), decoded.toOption.flatMap(_.perPeer.get(c)).map(_.removalPenalty))
  }

  test("node-local per-peer bookkeeping never reaches the signed peerHistory bytes") { res =>
    implicit val (js, h, sp) = res
    for {
      finished <- mkFinished
      one = mkOutcome(finished, SortedMap(c -> 2), SortedMap(b -> 3), SortedMap(ord(14L) -> 1L))
      other = mkOutcome(finished, SortedMap(a -> 9), SortedMap.empty, SortedMap(ord(14L) -> 2L))
      oneBytes <- JsonSerializer[IO].serialize(one.signedArtifactPeerHistory)
      otherBytes <- JsonSerializer[IO].serialize(other.signedArtifactPeerHistory)
    } yield expect(oneBytes.toVector == otherBytes.toVector, "signed peerHistory must not depend on node-local per-peer state") &&
      expect(one.signedArtifactPeerHistory.perPeer.isEmpty) &&
      expect(one.signedArtifactPeerHistory.recentRoundEndTimes.isEmpty) &&
      expect.same(recentProofSizes, one.signedArtifactPeerHistory.recentProofSizes) &&
      expect.same(Some(penaltyUntil), one.signedArtifactPeerHistory.penaltyUntil)
  }
}
