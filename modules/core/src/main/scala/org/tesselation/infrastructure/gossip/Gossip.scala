package org.tesselation.infrastructure.gossip

import java.security.KeyPair

import cats.effect.std.Queue
import cats.effect.{Async, Clock, Ref}
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.semigroup._

import scala.reflect.runtime.universe.TypeTag

import org.tesselation.domain.gossip.Gossip
import org.tesselation.ext.crypto._
import org.tesselation.ext.kryo._
import org.tesselation.kryo.KryoSerializer
import org.tesselation.schema.gossip._
import org.tesselation.schema.peer.PeerId
import org.tesselation.security.SecurityProvider

import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.PosLong

object Gossip {

  def make[F[_]: Async: Clock: SecurityProvider: KryoSerializer](
    rumorQueue: Queue[F, RumorBatch],
    nodeId: PeerId,
    keyPair: KeyPair
  ): F[Gossip[F]] =
    for {
      counter <- Ref.of[F, PosLong](PosLong(1L))
      time <- Clock[F].realTime
      generation <- PosLong.from(time.toMillis).leftMap(new RuntimeException(_)).liftTo[F]
    } yield make(counter, generation, rumorQueue, nodeId, keyPair)

  def make[F[_]: Async: Clock: SecurityProvider: KryoSerializer](
    counter: Ref[F, PosLong],
    generation: PosLong,
    rumorQueue: Queue[F, RumorBatch],
    nodeId: PeerId,
    keyPair: KeyPair
  ): Gossip[F] =
    new Gossip[F] {

      def spread[A <: AnyRef: TypeTag](rumorContent: A): F[Unit] =
        for {
          contentBinary <- rumorContent.toBinaryF
          count <- counter.getAndUpdate(_ |+| PosLong(1L))
          rumor = Rumor(nodeId, Ordinal(generation, count), contentBinary, ContentType.of[A])
          signedRumor <- rumor.sign(keyPair)
          hash <- rumor.hashF
          _ <- rumorQueue.offer(List(hash -> signedRumor))
        } yield ()
    }

}
