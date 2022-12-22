package org.tessellation.sdk.infrastructure.gossip

import java.security.KeyPair

import cats.effect.std.Queue
import cats.effect.{Async, Ref}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._

import scala.reflect.runtime.universe.TypeTag

import org.tessellation.ext.cats.syntax.next._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.ext.crypto._
import org.tessellation.schema.generation.Generation
import org.tessellation.schema.gossip._
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.security.{Hashed, SecurityProvider}
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.infrastructure.metrics.Metrics

import eu.timepit.refined.auto._
import io.circe.Encoder
import io.circe.syntax._
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Gossip {

  def make[F[_]: Async: SecurityProvider: KryoSerializer: Metrics](
    rumorQueue: Queue[F, Hashed[RumorRaw]],
    selfId: PeerId,
    generation: Generation,
    keyPair: KeyPair
  ): F[Gossip[F]] =
    Ref.of[F, Counter](Counter.MinValue).map { counter =>
      new Gossip[F] {

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

        private def signAndOffer(rumor: RumorRaw): F[Unit] =
          for {
            signedRumor <- rumor.sign(keyPair)
            hashedRumor <- signedRumor.toHashed
            _ <- rumorQueue.offer(hashedRumor)
            _ <- metrics.updateRumorsSpread(signedRumor)
            _ <- logSpread(hashedRumor)
          } yield ()

        private def logSpread(hashedRumor: Hashed[RumorRaw]): F[Unit] =
          rumorLogger.info(
            s"Rumor spread {hash=${hashedRumor.hash.show}, rumor=${hashedRumor.signed.value.show}"
          )

      }
    }

}
