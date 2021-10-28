package org.tesselation.infrastructure.gossip

import java.security.KeyPair

import cats.effect.std.Queue
import cats.effect.{Async, Ref}
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.semigroup._

import scala.reflect.runtime.universe._

import org.tesselation.domain.cluster.storage.SessionStorage
import org.tesselation.domain.gossip.Gossip
import org.tesselation.ext.crypto._
import org.tesselation.ext.kryo._
import org.tesselation.kryo.KryoSerializer
import org.tesselation.schema.cluster.{SessionDoesNotExist, SessionToken}
import org.tesselation.schema.gossip.{Rumor, RumorBatch}
import org.tesselation.schema.peer.PeerId
import org.tesselation.security.SecurityProvider

import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.PosLong

object Gossip {

  def make[F[_]: Async: SecurityProvider: KryoSerializer](
    rumorQueue: Queue[F, RumorBatch],
    sessionStorage: SessionStorage[F],
    nodeId: PeerId,
    keyPair: KeyPair
  ): F[Gossip[F]] =
    Ref.of[F, PosLong](PosLong(1L)).map {
      make(_, rumorQueue, sessionStorage, nodeId, keyPair)
    }

  def make[F[_]: Async: SecurityProvider: KryoSerializer](
    counter: Ref[F, PosLong],
    rumorQueue: Queue[F, RumorBatch],
    sessionStorage: SessionStorage[F],
    nodeId: PeerId,
    keyPair: KeyPair
  ): Gossip[F] =
    new Gossip[F] {

      def spread[A <: AnyRef: TypeTag](rumorContent: A): F[Unit] =
        for {
          session <- sessionStorage.getToken.flatMap(_.fold(SessionDoesNotExist.raiseError[F, SessionToken])(_.pure[F]))
          contentBinary <- rumorContent.toBinaryF
          count <- counter
            .updateAndGet(_ |+| PosLong(1L))
          rumor = Rumor(typeOf[A].toString, nodeId, count, session, contentBinary)
          signedRumor <- rumor.sign(keyPair)
          hash <- rumor.hashF
          _ <- rumorQueue.offer(List(hash -> signedRumor))
        } yield ()
    }

}
