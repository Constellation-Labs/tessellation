package org.tessellation.infrastructure.gossip

import cats.Applicative
import cats.data.{Kleisli, OptionT}
import cats.effect.Async
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.reflect.ClassTag
import scala.reflect.runtime.universe.TypeTag

import org.tessellation.domain.gossip.RumorStorage
import org.tessellation.ext.kryo._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.gossip.{ContentType, ReceivedRumor, Rumor}
import org.tessellation.schema.peer.PeerId

object RumorHandler {

  def fromReceivedRumorFn[F[_]: Async: KryoSerializer, A <: AnyRef: TypeTag: ClassTag](
    latestOnly: Boolean = false
  )(f: ReceivedRumor[A] => F[Unit]): RumorHandler[F] = {
    val handlerContentType = ContentType.of[A]
    val pf = new PartialFunction[(Rumor, RumorStorage[F]), F[Unit]] {
      def isDefinedAt(v: (Rumor, RumorStorage[F])): Boolean = v match {
        case (rumor, _) => rumor.contentType === handlerContentType
      }

      def apply(v: (Rumor, RumorStorage[F])): F[Unit] = v match {
        case (rumor, rumorStorage) =>
          for {
            content <- rumor.content.fromBinaryF[A]
            fn = f(ReceivedRumor(rumor.origin, content))
            _ <- if (latestOnly)
              rumorStorage
                .tryUpdateOrdinal(rumor)
                .ifM(fn, Applicative[F].unit)
            else
              fn
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
