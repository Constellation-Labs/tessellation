package org.tessellation.sdk.infrastructure.gossip

import java.security.KeyPair

import cats.effect.std.Queue
import cats.effect.{Async, Clock, Ref}
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.semigroup._
import cats.syntax.show._

import scala.reflect.runtime.universe.TypeTag

import org.tessellation.ext.crypto._
import org.tessellation.ext.kryo._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.gossip._
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.PosLong
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Gossip {

  def make[F[_]: Async: SecurityProvider: KryoSerializer: Metrics](
    rumorQueue: Queue[F, RumorBatch],
    nodeId: PeerId,
    keyPair: KeyPair
  ): F[Gossip[F]] =
    for {
      counter <- Ref.of[F, PosLong](PosLong(1L))
      time <- Clock[F].realTime
      generation <- PosLong.from(time.toMillis).leftMap(new RuntimeException(_)).liftTo[F]
    } yield make(counter, generation, rumorQueue, nodeId, keyPair)

  def make[F[_]: Async: SecurityProvider: KryoSerializer: Metrics](
    counter: Ref[F, PosLong],
    generation: PosLong,
    rumorQueue: Queue[F, RumorBatch],
    selfId: PeerId,
    keyPair: KeyPair
  ): Gossip[F] =
    new Gossip[F] {

      private val rumorLogger = Slf4jLogger.getLoggerFromName[F](rumorLoggerName)

      def spread[A <: AnyRef: TypeTag](rumorContent: A): F[Unit] =
        for {
          contentBinary <- rumorContent.toBinaryF
          count <- counter.getAndUpdate(_ |+| PosLong(1L))
          rumor = PeerRumorBinary(selfId, Ordinal(generation, count), contentBinary, ContentType.of[A])
          _ <- signAndOffer(rumor)
        } yield ()

      def spreadCommon[A <: AnyRef: TypeTag](rumorContent: A): F[Unit] =
        for {
          contentBinary <- rumorContent.toBinaryF
          rumor = CommonRumorBinary(contentBinary, ContentType.of[A])
          _ <- signAndOffer(rumor)
        } yield ()

      private def signAndOffer(rumor: RumorBinary): F[Unit] =
        for {
          signedRumor <- rumor.sign(keyPair)
          hash <- rumor.hashF
          _ <- rumorQueue.offer(List(hash -> signedRumor))
          _ <- metrics.updateRumorsSpread(signedRumor)
          _ <- logSpread(hash, signedRumor)
        } yield ()

      private def logSpread(hash: Hash, signedRumor: Signed[RumorBinary]): F[Unit] =
        rumorLogger.info(
          s"Rumor spread {hash=${hash.show}, contentType=${signedRumor.contentType}}"
        )

    }

}
