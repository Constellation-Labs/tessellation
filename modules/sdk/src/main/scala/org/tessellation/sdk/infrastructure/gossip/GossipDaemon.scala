package org.tessellation.sdk.infrastructure.gossip

import cats.effect.std.{Queue, Random}
import cats.effect.{Async, Spawn}
import cats.syntax.all._
import cats.{Applicative, Parallel}

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.generation.Generation
import org.tessellation.schema.gossip._
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.{Peer, PeerId}
import org.tessellation.sdk.config.types.GossipDaemonConfig
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.collateral.Collateral
import org.tessellation.sdk.infrastructure.gossip.RumorStorage
import org.tessellation.sdk.infrastructure.gossip.RumorStorage.{AddSuccess, CounterTooHigh}
import org.tessellation.sdk.infrastructure.gossip.p2p.GossipClient
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hashed, SecurityProvider}

import eu.timepit.refined.auto._
import fs2.Stream
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait GossipDaemon[F[_]] {

  def startAsInitialValidator: F[Unit]

  def startAsRegularValidator: F[Unit]

}

object GossipDaemon {

  def make[F[_]: Async: SecurityProvider: KryoSerializer: Random: Parallel: Metrics](
    rumorStorage: RumorStorage[F],
    rumorQueue: Queue[F, Hashed[RumorRaw]],
    clusterStorage: ClusterStorage[F],
    gossipClient: GossipClient[F],
    rumorHandler: RumorHandler[F],
    rumorValidator: RumorValidator[F],
    selfId: PeerId,
    generation: Generation,
    cfg: GossipDaemonConfig,
    collateral: Collateral[F]
  ): GossipDaemon[F] = {
    new GossipDaemon[F] {
      private val logger = Slf4jLogger.getLogger[F]
      private val rumorLogger = Slf4jLogger.getLoggerFromName[F](rumorLoggerName)

      private val peerRoundRunner = GossipRoundRunner.make(clusterStorage, peerRound, "peer", cfg.peerRound)
      private val commonRoundRunner = GossipRoundRunner.make(clusterStorage, commonRound, "common", cfg.commonRound)

      def startAsInitialValidator: F[Unit] =
        peerRoundRunner.runForever >>
          commonRoundRunner.runForever >>
          consumeRumors

      def startAsRegularValidator: F[Unit] =
        Spawn[F].start {
          clusterStorage.peerChanges.collectFirst {
            case (_, Some(peer)) if peer.state === NodeState.Ready => peer
          }.compile.lastOrError.flatMap { peer =>
            initPeerRumorStorage(peer) >>
              peerRoundRunner.runForever >>
              initCommonRumorStorage(peer) >>
              commonRoundRunner.runForever >>
              consumeRumors
          }
        }.void

      private def consumeRumors: F[Unit] =
        Spawn[F].start {
          Stream
            .fromQueueUnterminated(rumorQueue)
            .evalTap(logConsumption)
            .evalFilter(validateRumor)
            .evalFilter(tryAddRumorToStore)
            .evalFilter(verifyCollateral)
            .evalMap(handleRumor)
            .handleErrorWith { err =>
              Stream.eval(logger.error(err)(s"Unexpected error in gossip")) >> Stream.raiseError(err)
            }
            .compile
            .drain
        }.void

      private def logConsumption(hashedRumor: Hashed[RumorRaw]): F[Unit] =
        rumorLogger.info(
          s"Rumor consumed {hash=${hashedRumor.hash.show}, rumor=${hashedRumor.signed.value.show}}"
        )

      private def validateRumor(hashedRumor: Hashed[RumorRaw]): F[Boolean] =
        rumorValidator
          .validate(hashedRumor.signed)
          .flatTap { signedRumorV =>
            Applicative[F]
              .whenA(signedRumorV.isInvalid)(
                logger.warn(s"Discarding invalid rumor {hash=${hashedRumor.hash.show}, reason=${signedRumorV.show}")
              )
          }
          .map(_.isValid)

      private def verifyCollateral(hashedRumor: Hashed[RumorRaw]): F[Boolean] =
        hashedRumor.signed.proofs.toNonEmptyList
          .map(_.id)
          .map(PeerId.fromId)
          .forallM(collateral.hasCollateral)
          .flatTap(
            logger
              .warn(
                s"Discarding rumor due to not sufficient collateral {hash=${hashedRumor.hash.show}}"
              )
              .unlessA
          )

      private def tryAddRumorToStore(hashedRumor: Hashed[RumorRaw]): F[Boolean] =
        hashedRumor.signed.value match {
          case rumor: PeerRumorRaw =>
            rumorStorage
              .addPeerRumorIfConsecutive(hashedRumor.signed.asInstanceOf[Signed[PeerRumorRaw]])
              .flatTap { result =>
                rumorQueue
                  .offer(hashedRumor)
                  .whenA(
                    result === CounterTooHigh && rumor.origin === selfId && rumor.ordinal.generation === generation
                  )
              }
              .map(_ === AddSuccess)
          case _ => rumorStorage.addCommonRumorIfUnseen(hashedRumor.asInstanceOf[Hashed[CommonRumorRaw]])
        }

      private def handleRumor(hashedRumor: Hashed[RumorRaw]): F[Unit] =
        rumorHandler
          .run((hashedRumor.signed.value, selfId))
          .semiflatTap(_ => metrics.updateRumorsConsumed("success", hashedRumor))
          .getOrElseF {
            logger.debug(s"Unhandled rumor {hash=${hashedRumor.hash.show}}") >>
              metrics.updateRumorsConsumed("unhandled", hashedRumor)
          }
          .handleErrorWith { err =>
            logger.error(err)(s"Error handling rumor {hash=${hashedRumor.hash.show}}") >>
              metrics.updateRumorsConsumed("error", hashedRumor)
          }

      private def initPeerRumorStorage(peer: Peer): F[Unit] =
        gossipClient.getInitialPeerRumors
          .run(peer)
          .evalFilter(_.toHashed >>= validateRumor)
          .evalMap(rumorStorage.setInitialPeerRumor)
          .compile
          .drain

      private def initCommonRumorStorage(peer: Peer): F[Unit] =
        gossipClient.getInitialCommonRumorHashes.run(peer).flatMap { response =>
          rumorStorage.setInitialCommonRumorSeenHashes(response.seen)
        }

      private def peerRound(peer: Peer): F[Unit] =
        rumorStorage.getPeerRumorHeadCounters.flatMap { headCounters =>
          gossipClient
            .queryPeerRumors(PeerRumorInquiryRequest(headCounters))
            .run(peer)
            .evalMap(_.toHashed)
            .enqueueUnterminated(rumorQueue)
            .compile
            .drain
        }

      private def commonRound(peer: Peer): F[Unit] =
        for {
          commonRumorOffer <- gossipClient.getCommonRumorOffer.run(peer)
          remoteHashes = commonRumorOffer.offer
          localHashes <- rumorStorage.getCommonRumorSeenHashes
          missingHashes = remoteHashes.diff(localHashes)

          _ <- Applicative[F].whenA(missingHashes.nonEmpty) {
            gossipClient
              .queryCommonRumors(QueryCommonRumorsRequest(missingHashes))
              .run(peer)
              .evalMap(_.toHashed)
              .enqueueUnterminated(rumorQueue)
              .compile
              .drain
          }
        } yield ()
    }
  }
}
