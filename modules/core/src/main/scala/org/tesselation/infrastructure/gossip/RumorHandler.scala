package org.tesselation.infrastructure.gossip

import cats.Applicative
import cats.data.{Kleisli, OptionT}
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.order._
import cats.syntax.traverse._

import scala.reflect.ClassTag
import scala.reflect.runtime.universe._

import org.tesselation.domain.gossip.RumorStorage
import org.tesselation.ext.kryo._
import org.tesselation.kryo.KryoSerializer
import org.tesselation.schema._
import org.tesselation.schema.gossip.{ReceivedRumor, Rumor}
import org.tesselation.schema.peer.PeerId

object RumorHandler {

  def fromReceivedRumorFn[F[_]: Async: KryoSerializer, A <: AnyRef: TypeTag: ClassTag](
    latestOnly: Boolean = false
  )(f: ReceivedRumor[A] => F[Unit]): RumorHandler[F] = {
    val tpe = typeOf[A].toString
    val pf = new PartialFunction[(Rumor, RumorStorage[F]), F[Unit]] {
      override def isDefinedAt(v: (Rumor, RumorStorage[F])): Boolean = v match {
        case (rumor, _) => rumor.tpe == tpe
      }

      override def apply(v: (Rumor, RumorStorage[F])): F[Unit] = v match {
        case (rumor, rumorStorage) =>
          for {
            content <- rumor.content.fromBinaryF[A]
            seen <- rumorStorage.tryGetAndUpdateCounter(rumor)
            fn = f(ReceivedRumor(rumor.origin, content))
            _ <- if (latestOnly) seen.fold(fn)(s => if (s < rumor.counter) fn else Applicative[F].unit) else fn
          } yield ()
      }
    }

    Kleisli.apply[OptionT[F, *], (Rumor, RumorStorage[F]), Unit] {
      case (rumor, rumorStorage) =>
        OptionT(pf.lift((rumor, rumorStorage)).sequence)
    }
  }

  def fromBiFn[F[_]: Async: KryoSerializer, A <: AnyRef: TypeTag: ClassTag](
    f: (PeerId, A) => F[Unit]
  ): RumorHandler[F] = fromReceivedRumorFn() { r: ReceivedRumor[A] =>
    f(r.origin, r.content)
  }

  def fromFn[F[_]: Async: KryoSerializer, A <: AnyRef: TypeTag: ClassTag](
    f: A => F[Unit]
  ): RumorHandler[F] = fromBiFn { (_: PeerId, a: A) =>
    f(a)
  }

}
