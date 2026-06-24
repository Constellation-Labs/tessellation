package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.MonadThrow
import cats.data.NonEmptyList
import cats.effect.Async
import cats.effect.std.Random
import cats.effect.syntax.concurrent._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.list._

import scala.util.control.NoStackTrace

import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.snapshot.PeerSelect
import io.constellationnetwork.node.shared.http.p2p.clients.SnapshotClient
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.node.NodeState.{Observing, Ready, WaitingForReady}
import io.constellationnetwork.schema.peer.Peer.toP2PContext
import io.constellationnetwork.schema.peer.{L0Peer, Peer, PeerId}
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo}
import io.constellationnetwork.schema.trust.{TrustScores, TrustValueRefined, TrustValueRefinement}
import io.constellationnetwork.security.hash.Hash

import derevo.cats.show
import derevo.circe.magnolia.encoder
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import eu.timepit.refined.types.numeric.PosInt
import io.circe.syntax.EncoderOps
import org.typelevel.log4cats.slf4j.Slf4jLogger

object PeerSelect {
  val peerSelectLoggerName = "PeerSelectLogger"

  @derive(encoder, show)
  case class FilteredPeerDetails(
    initialPeers: NonEmptyList[Peer],
    latestOrdinals: NonEmptyList[SnapshotOrdinal],
    ordinalDistribution: List[(SnapshotOrdinal, NonEmptyList[Peer])],
    majorityOrdinal: SnapshotOrdinal,
    hashDistribution: List[(Hash, NonEmptyList[Peer])],
    peerCandidates: NonEmptyList[Peer],
    selectedPeer: L0Peer
  )

  val maxConcurrentPeerInquiries = 10
  val peerSampleRatio = 0.25
  val minSampleSize: PosInt = 20
  val defaultPeerTrustScore: TrustValueRefined = 1e-4

  case object NoPeersToSelect extends NoStackTrace
  case object NoValidPeersForRecoverySource extends NoStackTrace
  case object NoHashes extends NoStackTrace

  /** Recovery forward source-corroboration heuristic (pure; unit-tested in PeerSelectSuite).
    *
    * LIVENESS / EFFICIENCY ONLY -- this is NOT a fork-safety boundary. Fork-safety on recovery is enforced at the download/validation
    * layer: every downloaded snapshot is cryptographically signature-validated against the seedlist (see
    * `Download.validateSnapshotSignatures`), and an optional seedlist-signed recovery checkpoint pins the canonical `(ordinal, hash)` the
    * chain must pass through (see `RecoveryCheckpoint`). Source selection only chooses WHICH peer to download from; it cannot, on its own,
    * distinguish the canonical chain from a minority fork (the per-round committee is not available here), so it does not try to -- a
    * chosen source's snapshots are still fully validated on download.
    *
    * Given each responder's latest ordinal and the caller's local ordinal, decide which responders to keep as download-source candidates.
    * Returns the responders at the single most-common AHEAD ordinal when that ordinal is reported by a strict majority of responders;
    * otherwise returns the full responder list unchanged. This biases sourcing away from a lone peer that has raced ahead (avoids wasting a
    * download attempt on an uncorroborated tip), but it is a heuristic, not a safety guarantee. Inert when `minOrdinalExclusive` is `None`,
    * or when no responder is strictly ahead (rollback / already caught up).
    *
    * Determinism note: at most one ordinal can be a strict majority, so the `maxByOption` tiebreak only matters among sub-majority groups.
    */
  def corroboratedAheadPool[A](
    responded: List[(A, SnapshotOrdinal)],
    minOrdinalExclusive: Option[SnapshotOrdinal]
  ): List[(A, SnapshotOrdinal)] =
    minOrdinalExclusive.fold(responded) { local =>
      responded.filter { case (_, ordinal) => ordinal.value.value > local.value.value }.groupBy {
        case (_, ordinal) => ordinal
      }.toList.maxByOption { case (_, peersAtOrdinal) => peersAtOrdinal.size }.filter {
        case (_, peersAtOrdinal) => peersAtOrdinal.size * 2 > responded.size
      }.map { case (_, peersAtOrdinal) => peersAtOrdinal }
        .getOrElse(responded)
    }

  /** Keep only the peers whose advertised latest ordinal is at or above `ordinal` -- i.e. the peers that can actually serve the (ordinal,
    * hash) corroboration probe. A peer below it cannot: a recovering (not-Ready) peer returns 503 and a Ready-but-behind peer returns 404.
    * Sourcing the probe set from these live tips (rather than the ClusterStorage-state pool, which can be stale) avoids querying peers that
    * will fail; the point is that a stale-"Ready" laggard's 503 must not abort source selection (the mutual-503 recovery wedge). Pure;
    * unit-tested in PeerSelectSuite.
    */
  def peersAtOrAbove[A](responded: List[(A, SnapshotOrdinal)], ordinal: SnapshotOrdinal): List[A] =
    responded.collect { case (peer, peerOrdinal) if peerOrdinal.value.value >= ordinal.value.value => peer }

  def make[F[_]: Async: Random, S <: Snapshot, SI <: SnapshotInfo[_]](
    storage: ClusterStorage[F],
    snapshotClient: SnapshotClient[F, S, SI],
    getTrustScores: F[TrustScores]
  ): PeerSelect[F] = new PeerSelect[F] {

    val logger = Slf4jLogger.getLoggerFromName[F](peerSelectLoggerName)

    def select: F[L0Peer] = getFilteredPeerDetails(observingFallback = false, Set.empty, None)
      .flatTap(details => logger.debug(details.asJson.noSpaces))
      .map(_.selectedPeer)

    /** Recovery variant: try the Ready pool first; if it raises `NoPeersToSelect`, retry with the Observing pool, raising
      * `NoValidPeersForRecoverySource` if even that is empty. Lets callers in the recovery path distinguish "no Ready peer right now"
      * (often resolves on its own) from "no candidate source at all" (operator action needed). `preferredPeers` biases selection toward the
      * recovery-hint majority within the validated candidate set (see the trait scaladoc).
      */
    def selectForRecovery(preferredPeers: Set[PeerId], minOrdinalExclusive: Option[SnapshotOrdinal]): F[L0Peer] =
      getFilteredPeerDetails(observingFallback = false, preferredPeers, minOrdinalExclusive)
        .flatTap(details => logger.debug(details.asJson.noSpaces))
        .map(_.selectedPeer)
        .recoverWith {
          case NoPeersToSelect =>
            getFilteredPeerDetails(observingFallback = true, preferredPeers, minOrdinalExclusive)
              .flatTap(details => logger.debug(details.asJson.noSpaces))
              .map(_.selectedPeer)
              .recoverWith { case NoPeersToSelect => MonadThrow[F].raiseError(NoValidPeersForRecoverySource) }
        }

    def getFilteredPeerDetails: F[FilteredPeerDetails] = getFilteredPeerDetails(observingFallback = false, Set.empty, None)

    def getFilteredPeerDetails(
      observingFallback: Boolean,
      preferredPeers: Set[PeerId],
      minOrdinalExclusive: Option[SnapshotOrdinal]
    ): F[FilteredPeerDetails] = for {
      // WaitingForReady peers hold the same snapshot state as Ready peers (initFromDownload
      // already ran trySetInitialConsensusOutcome). Including them in the primary pool
      // prevents the post-rollback bottleneck where only the rollback-lead node is Ready
      // while sibling source nodes await a round to close: joining peers funnel through
      // the lone Ready peer for snapshot downloads and stall.
      peers <- storage.getResponsivePeers.map { all =>
        val primary = all.filter(p => p.state === Ready || p.state === WaitingForReady)
        if (primary.nonEmpty || !observingFallback) primary
        else all.filter(_.state === Observing)
      }
        .flatMap(getPeerSublist)
        .flatMap { peerSublist =>
          MonadThrow[F].fromOption(peerSublist.toNel, NoPeersToSelect)
        }
      // Tolerate per-peer failures here. A peer that is not Ready returns 503 on
      // /global-snapshots/latest/ordinal; left unhandled inside parTraverseN, one such failure
      // aborts the whole selection, so a single lagging/recovering peer poisons the pool and the
      // node can never pick a healthy source (observed: two laggards deadlock fetching from each
      // other). Drop the unresponsive peer instead -- mirrors getSnapshotHashByPeer's Option pattern.
      peerOrdinals <- peers.toList
        .parTraverseN(maxConcurrentPeerInquiries) { peer =>
          snapshotClient.getLatestOrdinal(peer).map(ordinal => Option((peer, ordinal))).handleError(_ => Option.empty)
        }
        .map(_.flatten)
        // Recovery forward source-corroboration (LIVENESS heuristic, not fork-safety -- downloaded snapshots
        // are signature-validated and optionally checkpoint-gated regardless of source; see
        // `corroboratedAheadPool`): when `minOrdinalExclusive` is set (recovery path), keep only the
        // responders at the single most-common ahead ordinal when that ordinal has a strict majority -- else
        // the full responder list. Biases sourcing away from a lone raced-ahead peer and breaks the
        // mutual-503 deadlock where equally-stuck peers pick each other. Inert for normal `select` (None).
        .map(corroboratedAheadPool(_, minOrdinalExclusive))
        .flatMap(responded => MonadThrow[F].fromOption(responded.toNel, NoPeersToSelect))
      latestOrdinals = peerOrdinals.map { case (_, ordinal) => ordinal }
      ordinalDistribution = peerOrdinals.groupMap { case (_, ordinal) => ordinal } { case (peer, _) => peer }
      (majorityOrdinal, _) = latestOrdinals.groupBy(identity).maxBy { case (_, ordinals) => ordinals.size }
      // Validate the (ordinal, hash) only against peers whose advertised tip is >= majorityOrdinal
      // (peersAtOrAbove): a peer below it cannot serve that ordinal. Crucially this also tolerates per-peer
      // failures -- mirroring the getLatestOrdinal probe above -- so one stale-"Ready" laggard's 503 cannot
      // abort the whole selection and wedge recovery (the mutual-503 deadlock). The source set is peerOrdinals
      // (the live tips just probed), not the possibly-stale ClusterStorage `peers` pool.
      peerDistribution <- peersAtOrAbove(peerOrdinals.toList, majorityOrdinal)
        .parTraverseN(maxConcurrentPeerInquiries)(peer => getSnapshotHashByPeer(peer, majorityOrdinal).handleError(_ => Option.empty))
        .flatMap { maybePeerSnapshotHashes =>
          MonadThrow[F].fromOption(
            maybePeerSnapshotHashes.flatten.toNel,
            NoHashes
          )
        }
        .map(_.groupMap { case (_, hash) => hash } { case (peer, _) => peer })
      validatedCandidates = peerDistribution.values.maxBy(_.length)
      // #8 recovery hint: bias toward the preferred (fork-recovery majority) peers by intersecting them with
      // the already-validated majority-ordinal/majority-hash candidates. Narrows only WITHIN the validated set
      // and falls back to the full set when the hint does not overlap. Empty hint => prior behavior.
      peerCandidates =
        if (preferredPeers.isEmpty) validatedCandidates
        else validatedCandidates.filter(p => preferredPeers.contains(p.id)).toNel.getOrElse(validatedCandidates)
      selectedPeer <- Random[F].elementOf(peerCandidates.toList).map(L0Peer.fromPeer)
    } yield
      FilteredPeerDetails(
        peers,
        latestOrdinals,
        ordinalDistribution.toList,
        majorityOrdinal,
        peerDistribution.toList,
        peerCandidates,
        selectedPeer
      )

    def getPeerSublist(peers: Set[Peer]): F[List[Peer]] = {
      val sampleSize = Math.max((peers.size * peerSampleRatio).toInt, minSampleSize)

      for {
        scores <- getTrustScores.map(_.scores)
        refinedScores = scores.view
          .mapValues(score => refineV[TrustValueRefinement](score))
          .collect {
            case (key, Right(s)) =>
              key -> s
          }
          .toMap
        candidates = peers.map { p =>
          p -> refinedScores.getOrElse(p.id, defaultPeerTrustScore)
        }.toMap
        size <- MonadThrow[F].fromEither(
          refineV[Positive](sampleSize).leftMap(new IllegalStateException(_))
        )
        samples <- WeightedProspect.sample(candidates, size)
      } yield samples
    }

    def getSnapshotHashByPeer(peer: Peer, ordinal: SnapshotOrdinal): F[Option[(Peer, Hash)]] =
      snapshotClient.getHash(ordinal).run(peer).map(_.map((peer, _)))
  }
}
