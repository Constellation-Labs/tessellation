package org.tessellation.sdk.infrastructure.gossip

import cats.data.Ior
import cats.effect.Async
import cats.effect.std.{Queue, Random, Supervisor}
import cats.syntax.all._
import cats.{Applicative, Parallel}

import org.tessellation.ext.cats.syntax.next._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.generation.Generation
import org.tessellation.schema.gossip._
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.{Peer, PeerId}
import org.tessellation.schema.security.signature.Signed
import org.tessellation.schema.security.{Hashed, SecurityProvider}
import org.tessellation.sdk.config.types.GossipDaemonConfig
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.collateral.Collateral
import org.tessellation.sdk.domain.healthcheck.LocalHealthcheck
import org.tessellation.sdk.infrastructure.gossip.RumorStorage
import org.tessellation.sdk.infrastructure.gossip.RumorStorage.{AddSuccess, CounterTooHigh}
import org.tessellation.sdk.infrastructure.gossip.p2p.GossipClient
import org.tessellation.sdk.infrastructure.metrics.Metrics

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
    localHealthcheck: LocalHealthcheck[F],
    selfId: PeerId,
    generation: Generation,
    cfg: GossipDaemonConfig,
    collateral: Collateral[F]
  )(implicit S: Supervisor[F]): GossipDaemon[F] = {
    new GossipDaemon[F] {
      private val logger = Slf4jLogger.getLogger[F]
      private val rumorLogger = Slf4jLogger.getLoggerFromName[F](rumorLoggerName)

      def startAsInitialValidator: F[Unit] =
        runPeerRoundRunner >>
          runCommonRoundRunner >>
          consumeRumors

      def startAsRegularValidator: F[Unit] =
        S.supervise {
          clusterStorage.peerChanges.collectFirst {
            case Ior.Right(peer) if peer.state === NodeState.Ready   => peer
            case Ior.Both(_, peer) if peer.state === NodeState.Ready => peer
          }.compile.lastOrError.flatMap { peer =>
            initPeerRumorStorage(peer) >>
              runPeerRoundRunner >>
              initCommonRumorStorage(peer) >>
              runCommonRoundRunner >>
              consumeRumors
          }
        }.void

      private def runPeerRoundRunner =
        GossipRoundRunner.make(clusterStorage, localHealthcheck, peerRound, "peer", cfg.peerRound).flatMap(_.runForever)
      private def runCommonRoundRunner =
        GossipRoundRunner.make(clusterStorage, localHealthcheck, commonRound, "common", cfg.commonRound).flatMap(_.runForever)

      private def consumeRumors: F[Unit] =
        S.supervise {
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
        hashedRumor.signed.proofs.toList
          .map(_.id)
          .map(PeerId.fromId)
          .partitionEitherM(peerId => collateral.hasCollateral(peerId).map(Either.cond(_, peerId, peerId)))
          .flatMap {
            case (signersWithoutCollateral, _) =>
              val result = signersWithoutCollateral.isEmpty
              logger
                .warn(
                  s"Discarding rumor due to insufficient collateral {hash=${hashedRumor.hash.show}, signers=${signersWithoutCollateral.show}}"
                )
                .unlessA(result)
                .as(result)
          }

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
          .evalMap(_.toHashed)
          .evalTap { hashedRumor =>
            rumorLogger.info(s"Initializing rumor {hash=${hashedRumor.hash.show}, rumor=${hashedRumor.signed.value.show}}")
          }
          .evalFilter(validateRumor)
          .evalMap(hashedRumor => rumorStorage.setInitialPeerRumor(hashedRumor.signed))
          .compile
          .drain

      private def initCommonRumorStorage(peer: Peer): F[Unit] =
        gossipClient.getInitialCommonRumorHashes.run(peer).flatMap { response =>
          rumorStorage.setInitialCommonRumorSeenHashes(response.seen)
        }

      private def peerRound(peer: Peer): F[Unit] =
        rumorStorage.getLastPeerOrdinals.flatMap { lastOrdinals =>
          val nextOrdinals = lastOrdinals.view
            .mapValues(o => Ordinal(o.generation, o.counter.next))
            .toMap

          gossipClient
            .queryPeerRumors(PeerRumorInquiryRequest(nextOrdinals))
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
