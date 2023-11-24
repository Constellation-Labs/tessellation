package org.tessellation.sdk.infrastructure.gossip

import cats.Applicative
import cats.data.{Kleisli, OptionT}
import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.reflect.runtime.universe.TypeTag

import org.tessellation.schema.gossip._
import org.tessellation.schema.peer.PeerId
import org.tessellation.syntax.boolean._

import io.circe.Decoder

object RumorHandler {

  def fromCommonRumorConsumer[F[_]: Async, A: TypeTag: Decoder](
    f: CommonRumor[A] => F[Unit]
  ): RumorHandler[F] = {
    val handlerContentType = ContentType.of[A]
    val pf = new PartialFunction[(RumorRaw, PeerId), F[Unit]] {
      def isDefinedAt(v: (RumorRaw, PeerId)): Boolean = v match {
        case (rumor, _) =>
          rumor.isInstanceOf[CommonRumorRaw] &&
          rumor.contentType === handlerContentType
      }

      def apply(v: (RumorRaw, PeerId)): F[Unit] = v match {
        case (rumor: CommonRumorRaw, _) =>
          for {
            content <- rumor.content.as[A].liftTo[F]
            fn = f(CommonRumor(content))
            _ <- fn
          } yield ()
        case (r, _) => UnexpectedRumorClass(r).raiseError[F, Unit]
      }
    }

    pfToKleisli(pf)
  }

  def fromPeerRumorConsumer[F[_]: Async, A: TypeTag: Decoder](
    selfOriginPolicy: OriginPolicy = IncludeSelfOrigin
  )(f: PeerRumor[A] => F[Unit]): RumorHandler[F] = {
    val handlerContentType = ContentType.of[A]
    val pf = new PartialFunction[(RumorRaw, PeerId), F[Unit]] {
      def isDefinedAt(v: (RumorRaw, PeerId)): Boolean = v match {
        case (rumor, selfId) =>
          rumor.isInstanceOf[PeerRumorRaw] &&
          rumor.contentType === handlerContentType &&
          ((rumor.asInstanceOf[PeerRumorRaw].origin === selfId) ==> (selfOriginPolicy =!= ExcludeSelfOrigin))
      }

      def apply(v: (RumorRaw, PeerId)): F[Unit] = v match {
        case (rumor: PeerRumorRaw, selfId) =>
          for {
            content <- rumor.content.as[A].liftTo[F]
            fn = f(PeerRumor(rumor.origin, rumor.ordinal, content))
            _ <-
              if (rumor.origin === selfId && selfOriginPolicy === IgnoreSelfOrigin)
                Applicative[F].unit
              else
                fn
          } yield ()
        case (r, _) => UnexpectedRumorClass(r).raiseError[F, Unit]
      }
    }

    pfToKleisli(pf)
  }

  private def pfToKleisli[A, F[_]: Applicative](
    pf: PartialFunction[(RumorRaw, PeerId), F[Unit]]
  ): Kleisli[OptionT[F, *], (RumorRaw, PeerId), Unit] =
    Kleisli.apply[OptionT[F, *], (RumorRaw, PeerId), Unit] {
      case (rumor, selfId) =>
        OptionT(pf.lift((rumor, selfId)).sequence)
    }

}
