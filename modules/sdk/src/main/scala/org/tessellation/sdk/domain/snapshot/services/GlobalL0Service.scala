package org.tessellation.sdk.domain.snapshot.services

import java.lang.Math.ceil

import cats.data._
import cats.effect.Async
import cats.effect.syntax.concurrent._
import cats.syntax.all._
import cats.{Applicative, Show}

import scala.collection.immutable.SortedSet
import scala.util.Random
import scala.util.control.NoStackTrace

import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.collection.FoldableOps.pickMajority
import org.tessellation.kryo.KryoSerializer
import org.tessellation.merkletree.StateProofValidator
import org.tessellation.schema._
import org.tessellation.schema.peer.{L0Peer, PeerId}
import org.tessellation.sdk.domain.cluster.storage.L0ClusterStorage
import org.tessellation.sdk.domain.snapshot.Validator.isNextSnapshot
import org.tessellation.sdk.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.clients.L0GlobalSnapshotClient
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hashed, SecurityProvider}

import eu.timepit.refined.auto.autoUnwrap
import eu.timepit.refined.types.numeric.PosLong
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait GlobalL0Service[F[_]] {
  type LatestSnapshotTuple = (Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)
  def pullLatestSnapshot: F[LatestSnapshotTuple]
  def pullGlobalSnapshots: F[Either[LatestSnapshotTuple, List[Hashed[GlobalIncrementalSnapshot]]]]
  def pullGlobalSnapshot(ordinal: SnapshotOrdinal): F[Option[Hashed[GlobalIncrementalSnapshot]]]
  def pullGlobalSnapshot(hash: Hash): F[Option[Hashed[GlobalIncrementalSnapshot]]]
}

object GlobalL0Service {
  case object NoMajorityPeers extends Exception("No majority peers found in storage") with NoStackTrace
  case object NoPeersWithMajorityHash extends Exception("No peers returned snapshot with hash in the majority") with NoStackTrace
  case object NoMajoritySnapshotData extends Exception("Unable to determine latest snapshot data for majority") with NoStackTrace
  case object NoPeerAlignedWithMajority extends Exception("No peer available that is aligned with majority") with NoStackTrace

  def make[
    F[_]: Async: KryoSerializer: SecurityProvider
  ](
    l0GlobalSnapshotClient: L0GlobalSnapshotClient[F],
    globalL0ClusterStorage: L0ClusterStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    singlePullLimit: Option[PosLong],
    maybeMajorityPeerIdSet: Option[NonEmptySet[PeerId]]
  ): GlobalL0Service[F] =
    new GlobalL0Service[F] {

      private val numConcurrentQueries = 10
      private val logger = Slf4jLogger.getLogger[F]
      private val maybeMajorityPeerIds = maybeMajorityPeerIdSet.map(_.toNonEmptyList)
      private val ordinalRange = 0L to 3L

      implicit val hashShow: Show[Hash] = Hash.shortShow

      def pullLatestSnapshot: F[LatestSnapshotTuple] =
        maybeMajorityPeerIds.fold(pullLatestSnapshotFromRandomPeer)(pullLatestSnapshotWithMajorityHash)

      def pullGlobalSnapshot(hash: Hash): F[Option[Hashed[GlobalIncrementalSnapshot]]] =
        pullGlobalSnapshot(l0GlobalSnapshotClient.get(hash)).handleErrorWith { e =>
          logger
            .warn(e)(s"Failure pulling single snapshot with hash=${hash.show}")
            .as(none)
        }

      def pullGlobalSnapshot(ordinal: SnapshotOrdinal): F[Option[Hashed[GlobalIncrementalSnapshot]]] =
        pullGlobalSnapshot(l0GlobalSnapshotClient.get(ordinal)).handleErrorWith { e =>
          logger
            .warn(e)(s"Failure pulling single snapshot with ordinal=${ordinal.show}")
            .as(none)
        }

      def pullGlobalSnapshots: F[Either[LatestSnapshotTuple, List[Hashed[GlobalIncrementalSnapshot]]]] =
        maybeMajorityPeerIds.fold(pullGlobalSnapshotsFromRandomPeer)(pullGlobalSnapshotsFromMajority)

      private def pullGlobalSnapshotsFromMajority(
        majorityPeerIds: NonEmptyList[PeerId]
      ): F[Either[LatestSnapshotTuple, List[Hashed[GlobalIncrementalSnapshot]]]] =
        lastGlobalSnapshotStorage.getOrdinal.flatMap {
          _.fold {
            pullLatestSnapshotWithMajorityHash(majorityPeerIds).map(_.asLeft[List[Hashed[GlobalIncrementalSnapshot]]])
          } { lastStoredOrdinal =>
            for {
              msd <- getMajoritySnapshotData(majorityPeerIds, lastStoredOrdinal.next)
              l0Peers <- globalL0ClusterStorage.getPeers
              pulled <- pullVerifiedSnapshots(lastStoredOrdinal.next, msd, l0Peers)
            } yield pulled.asRight[LatestSnapshotTuple]
          }
        }.handleErrorWith { e =>
          logger
            .warn(e)(s"Failure pulling global snapshots from majority")
            .as(
              List.empty[Hashed[GlobalIncrementalSnapshot]].asRight[LatestSnapshotTuple]
            )
        }

      private def pullLatestSnapshotWithMajorityHash(
        majorityPeerIds: NonEmptyList[PeerId]
      ): F[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)] = {
        type Agg = SortedSet[L0Peer]
        type Result = LatestSnapshotTuple
        for {
          peers <- globalL0ClusterStorage.getPeers.map(nes => Random.shuffle(nes.toSortedSet))
          majorityPeers <- getL0Peers(majorityPeerIds)
          result <- peers.tailRecM[F, Result] { l0Peers =>
            l0Peers.headOption.fold {
              NoPeersWithMajorityHash.raiseError[F, Either[Agg, Result]]
            } { l0Peer =>
              pullLatestSnapshotFromPeer(l0Peer).flatMap { latest =>
                Applicative[F].ifF(verifyLatestSnapshot(latest, majorityPeers))(
                  latest.asRight[Agg],
                  l0Peers.tail.asLeft[Result]
                )
              }.handleErrorWith { err =>
                logger
                  .warn(err)(s"Error pulling latest snapshot from peer ${l0Peer.show}")
                  .as(l0Peers.tail.asLeft[Result])
              }
            }
          }
        } yield result
      }

      private def verifyLatestSnapshot(
        snapshotTuple: LatestSnapshotTuple,
        majorityPeers: NonEmptyList[L0Peer]
      ): F[Boolean] = {
        val (snapshot, info) = snapshotTuple
        List(
          StateProofValidator.validate(snapshot, info).map(_.isValid),
          getMajorityOrdinal(majorityPeers).map(_.fold(false)(ord => ordinalRange.contains(ord.value - snapshot.ordinal.value))),
          getMajorityHash(majorityPeers, snapshot.ordinal).map(_.contains(snapshot.hash))
        ).forallM(identity)
      }

      private def pullLatestSnapshotFromRandomPeer: F[LatestSnapshotTuple] =
        globalL0ClusterStorage.getRandomPeer >>= pullLatestSnapshotFromPeer

      private def pullLatestSnapshotFromPeer(l0Peer: L0Peer): F[LatestSnapshotTuple] =
        l0GlobalSnapshotClient.getLatest(l0Peer).flatMap {
          case (snapshot, state) =>
            snapshot.toHashedWithSignatureCheck.flatMap(_.liftTo[F]).map((_, state))
        }

      private def pullGlobalSnapshot(
        peerResponse: PeerResponse.PeerResponse[F, Signed[GlobalIncrementalSnapshot]]
      ): F[Option[Hashed[GlobalIncrementalSnapshot]]] =
        globalL0ClusterStorage.getRandomPeer.flatMap { l0Peer =>
          peerResponse(l0Peer)
            .flatMap(_.toHashedWithSignatureCheck.flatMap(_.liftTo[F]))
            .map(_.some)
        }

      private def pullGlobalSnapshotsFromRandomPeer: F[Either[LatestSnapshotTuple, List[Hashed[GlobalIncrementalSnapshot]]]] =
        lastGlobalSnapshotStorage.getOrdinal.flatMap {
          _.fold {
            pullLatestSnapshotFromRandomPeer.map(_.asLeft[List[Hashed[GlobalIncrementalSnapshot]]])
          } { lastStoredOrdinal =>
            for {
              l0Peer <- globalL0ClusterStorage.getRandomPeer
              latestOrdinal <- l0GlobalSnapshotClient.getLatestOrdinal.run(l0Peer)
              nextOrdinal = lastStoredOrdinal.next
              lastOrdinal = calculateLastOrdinal(nextOrdinal, latestOrdinal)
              pulled <- pullSnapshots(l0Peer, nextOrdinal, lastOrdinal)
            } yield pulled.toList.asRight[LatestSnapshotTuple]
          }
        }.handleErrorWith { e =>
          logger
            .warn(e)("Failure pulling global snapshots from random peer")
            .as(
              List.empty[Hashed[GlobalIncrementalSnapshot]].asRight[LatestSnapshotTuple]
            )
        }

      private def calculateLastOrdinal(nextOrdinal: SnapshotOrdinal, latestOrdinal: SnapshotOrdinal): SnapshotOrdinal =
        SnapshotOrdinal.unsafeApply(
          latestOrdinal.value.value
            .min(
              singlePullLimit
                .map(nextOrdinal.value.value + _.value)
                .getOrElse(latestOrdinal.value.value)
            )
        )

      private def pullSnapshots(
        l0Peer: L0Peer,
        nextOrdinal: SnapshotOrdinal,
        lastOrdinal: SnapshotOrdinal
      ): F[Chain[Hashed[GlobalIncrementalSnapshot]]] = {
        val ordinals = LazyList
          .range(nextOrdinal.value.value, lastOrdinal.value.value + 1)
          .map(SnapshotOrdinal.unsafeApply)

        type Success = Hashed[GlobalIncrementalSnapshot]
        type Result = Chain[Success]
        type Agg = (LazyList[SnapshotOrdinal], Result)
        (ordinals, Chain.empty[Success]).tailRecM[F, Result] {
          case (ordinal #:: nextOrdinals, snapshots) =>
            l0GlobalSnapshotClient
              .get(ordinal)(l0Peer)
              .flatMap(_.toHashedWithSignatureCheck.flatMap(_.liftTo[F]))
              .map(s => (nextOrdinals, snapshots :+ s).asLeft[Result])
              .handleErrorWith { e =>
                logger
                  .warn(e)(s"Failure pulling snapshot with ordinal=${ordinal.show}")
                  .as(snapshots.asRight[Agg])
              }

          case (_, snapshots) => snapshots.asRight[Agg].pure[F]
        }
      }

      private case class MajoritySnapshotData(ordinal: SnapshotOrdinal, hash: Hash)

      private def getMajoritySnapshotData(peerIds: NonEmptyList[PeerId], nextOrdinal: SnapshotOrdinal): F[MajoritySnapshotData] = {
        val maybeData =
          for {
            peers <- OptionT.liftF(getL0Peers(peerIds))
            majorityOrdinal <- OptionT(getMajorityOrdinal(peers))
            lastOrdinal = calculateLastOrdinal(nextOrdinal, majorityOrdinal)
            majorityHash <- OptionT(getMajorityHash(peers, lastOrdinal))
          } yield MajoritySnapshotData(lastOrdinal, majorityHash)

        maybeData.getOrElseF(NoMajoritySnapshotData.raiseError[F, MajoritySnapshotData])
      }

      private def getMajorityHash(peers: NonEmptyList[L0Peer], ordinal: SnapshotOrdinal): F[Option[Hash]] =
        peers.toList
          .parTraverseN(numConcurrentQueries) { p =>
            l0GlobalSnapshotClient
              .getHash(ordinal)
              .run(p)
              .handleErrorWith(logger.warn(_)(s"Unable to obtain hash for majority peer ${p.show}").as(none[Hash]))
          }
          .map(_.flatten)
          .flatTap(hashes => logger.debug(s"** Majority Hashes ${hashes.map(_.show)}"))
          .map(selectMajorityHash)

      private def pullVerifiedSnapshots(
        nextOrdinal: SnapshotOrdinal,
        msd: MajoritySnapshotData,
        peerSet: NonEmptySet[L0Peer]
      ): F[List[Hashed[GlobalIncrementalSnapshot]]] = {
        type Agg = SortedSet[L0Peer]
        type Result = List[Hashed[GlobalIncrementalSnapshot]]
        Random
          .shuffle(peerSet.toSortedSet)
          .tailRecM[F, Result] { peers =>
            peers.headOption.fold {
              NoPeerAlignedWithMajority.raiseError[F, Either[Agg, Result]]
            } { peer =>
              for {
                sc <- pullSnapshots(peer, nextOrdinal, msd.ordinal)
                verified <- verifySnapshotChain(sc, msd)
              } yield Either.cond(verified, sc.toList, peers.tail)
            }
          }
      }

      private def verifySnapshotChain(chain: Chain[Hashed[GlobalIncrementalSnapshot]], msd: MajoritySnapshotData): F[Boolean] =
        chain.toList match {
          case Nil =>
            logger.debug("No snapshots to verify").as(false)
          case _ if !chain.lastOption.exists(_.hash === msd.hash) =>
            logger.warn("Last snapshot hash does not match majority").as(false)
          case ss if !ss.zip(ss.tail).forall { case (a, b) => isNextSnapshot[GlobalIncrementalSnapshot](a, b) } =>
            logger.warn("Pulled snapshots do not form a chain").as(false)
          case _ =>
            logger.debug("Snapshot chain verified").as(true)
        }

      private def getL0Peers(peerIds: NonEmptyList[PeerId]): F[NonEmptyList[L0Peer]] =
        OptionT(
          peerIds.traverse(globalL0ClusterStorage.getPeer).map(_.toList.flatten.toNel)
        ).getOrRaise(NoMajorityPeers)

      private def getMajorityOrdinal(peers: NonEmptyList[L0Peer]): F[Option[SnapshotOrdinal]] =
        peers.toList
          .parTraverseN(numConcurrentQueries) { p =>
            l0GlobalSnapshotClient.getLatestOrdinal
              .run(p)
              .map(_.some)
              .handleErrorWith(logger.warn(_)(s"Unable to retrieve ordinal for majority peer ${p.show}").as(none[SnapshotOrdinal]))
          }
          .map(_.flatten)
          .map(pickMajority[List, SnapshotOrdinal])

      private def selectMajorityHash(hashes: List[Hash]): Option[Hash] =
        maybeMajorityPeerIds
          .map(ids => ceil(0.5 * (1 + ids.size)).toInt)
          .flatMap(min => pickMajority(hashes).filter(h => hashes.count(_ === h) >= min))
    }
}
