package io.constellationnetwork.node.shared.infrastructure.gossip

import java.security.KeyPair

import cats.effect.std.Queue
import cats.effect.{Async, Ref}
import cats.syntax.all._

import scala.reflect.runtime.universe.TypeTag

import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.node.shared.domain.gossip.{Gossip => GossipAlg}
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.generation.Generation
import io.constellationnetwork.schema.gossip._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.{Hashed, Hasher, SecurityProvider}

import io.circe.Encoder
import io.circe.syntax._
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Gossip {

  def make[F[_]: Async: SecurityProvider: Hasher: Metrics](
    rumorQueue: Queue[F, Hashed[RumorRaw]],
    selfId: PeerId,
    generation: Generation,
    keyPair: KeyPair
  ): F[GossipAlg[F]] =
    for {
      counter <- Ref.of[F, Counter](Counter.MinValue)
      directPushRef <- Ref.of[F, Option[GossipAlg.DirectPushFn[F]]](None)
    } yield
      new GossipAlg[F] {

        private val rumorLogger = Slf4jLogger.getLoggerFromName[F](rumorLoggerName)

        def spread[A: TypeTag: Encoder](rumorContent: A): F[Unit] =
          for {
            contentJson <- rumorContent.asJson.pure[F]
            count <- counter.getAndUpdate(_.next)
            rumor = PeerRumorRaw(selfId, Ordinal(generation, count), contentJson, ContentType.of[A])
            _ <- signAndOffer(rumor)
          } yield ()

        def spreadCommon[A: TypeTag: Encoder](rumorContent: A): F[Unit] =
          for {
            contentJson <- rumorContent.asJson.pure[F]
            rumor = CommonRumorRaw(contentJson, ContentType.of[A])
            _ <- signAndOffer(rumor)
          } yield ()

        def spreadDirect[A: TypeTag: Encoder](rumorContent: A, targets: Set[PeerId]): F[Unit] =
          for {
            contentJson <- rumorContent.asJson.pure[F]
            count <- counter.getAndUpdate(_.next)
            rumor = PeerRumorRaw(selfId, Ordinal(generation, count), contentJson, ContentType.of[A])
            hashed <- signAndOfferReturn(rumor)
            maybeFn <- directPushRef.get
            _ <- maybeFn.traverse_(fn =>
              fn(hashed, targets.excl(selfId)).handleErrorWith(err => rumorLogger.warn(err)(s"Direct push failed, gossip will propagate"))
            )
          } yield ()

        def setDirectPushFn(fn: GossipAlg.DirectPushFn[F]): F[Unit] =
          directPushRef.set(fn.some)

        private def signAndOffer(rumor: RumorRaw): F[Unit] =
          signAndOfferReturn(rumor).void

        private def signAndOfferReturn(rumor: RumorRaw): F[Hashed[RumorRaw]] =
          for {
            signedRumor <- rumor.sign(keyPair)
            hashedRumor <- signedRumor.toHashed
            _ <- rumorQueue.offer(hashedRumor)
            _ <- metrics.updateRumorsSpread(signedRumor)
            _ <- logSpread(hashedRumor)
          } yield hashedRumor

        private def logSpread(hashedRumor: Hashed[RumorRaw]): F[Unit] =
          rumorLogger.info(
            s"Rumor spread {hash=${hashedRumor.hash.show}, rumor=${hashedRumor.signed.value.show}"
          )

      }

}
