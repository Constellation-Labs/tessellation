package org.tesselation.infrastructure.gossip

import java.security.KeyPair

import cats.effect.Async
import cats.effect.std.Queue
import cats.syntax.flatMap._
import cats.syntax.functor._

import scala.reflect.runtime.universe._

import org.tesselation.domain.gossip.Gossip
import org.tesselation.ext.crypto._
import org.tesselation.ext.kryo._
import org.tesselation.keytool.security.SecurityProvider
import org.tesselation.kryo.KryoSerializer
import org.tesselation.schema.gossip.{Rumor, RumorBatch}

object Gossip {

  def make[F[_]: Async: SecurityProvider: KryoSerializer](
    rumorQueue: Queue[F, RumorBatch],
    keyPair: KeyPair
  ): Gossip[F] =
    new Gossip[F] {
      override def spread[A <: AnyRef: TypeTag](rumorContent: A): F[Unit] =
        for {
          contentBinary <- rumorContent.toBinaryF
          rumor = Rumor(typeOf[A].toString, contentBinary)
          hash <- rumor.hashF
          signedRumor <- rumor.sign(keyPair)
          _ <- rumorQueue.offer(List(hash -> signedRumor))
        } yield ()
    }

}
