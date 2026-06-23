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

  /** Recovery forward source-corroboration gate (pure; unit-tested in PeerSelectSuite).
    *
    * Given each responder's latest ordinal and the caller's local ordinal, decide which responders to keep as download-source candidates.
    * Returns the responders at the single most-common AHEAD ordinal ONLY when that ordinal is reported by a STRICT MAJORITY of all
    * responders; otherwise returns the full responder list unchanged (FAIL CLOSED). So a sub-quorum minority that has run ahead (a fork),
    * or an ahead set split across ordinals, never causes us to follow it -- we do not converge the majority onto an uncorroborated minority
    * higher tip. The caller's existing majority-(ordinal,hash) validation then runs on whatever pool is returned, validating the hash at
    * the corroborated ordinal. Inert when `minOrdinalExclusive` is `None`, or when no responder is strictly ahead (rollback / already
    * caught up).
    *
    * Determinism note: at most one ordinal can be a strict majority, so the `maxByOption` tiebreak only matters among sub-majority groups,
    * which are all rejected by the filter -- the OUTCOME is fail-closed regardless of the tiebreak.
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
        // Recovery forward source-corroboration: when `minOrdinalExclusive` is set (recovery path), keep
        // only the corroborated ahead pool -- the responders at the single most-common ahead ordinal, and
        // only if that ordinal is a STRICT MAJORITY of responders -- else the full responder list (fail
        // closed). Breaks the mutual-503 deadlock where equally-stuck peers pick each other, without ever
        // following an uncorroborated minority higher tip. Inert for normal `select` (None). See
        // `corroboratedAheadPool`.
        .map(corroboratedAheadPool(_, minOrdinalExclusive))
        .flatMap(responded => MonadThrow[F].fromOption(responded.toNel, NoPeersToSelect))
      latestOrdinals = peerOrdinals.map { case (_, ordinal) => ordinal }
      ordinalDistribution = peerOrdinals.groupMap { case (_, ordinal) => ordinal } { case (peer, _) => peer }
      (majorityOrdinal, _) = latestOrdinals.groupBy(identity).maxBy { case (_, ordinals) => ordinals.size }
      peerDistribution <- peers
        .parTraverseN(maxConcurrentPeerInquiries)(getSnapshotHashByPeer(_, majorityOrdinal))
        .flatMap { maybePeerSnapshotHashes =>
          MonadThrow[F].fromOption(
            maybePeerSnapshotHashes.toList.flatten.toNel,
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
