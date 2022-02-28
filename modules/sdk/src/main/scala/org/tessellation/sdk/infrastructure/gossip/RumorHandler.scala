package org.tessellation.sdk.infrastructure.gossip

import cats.Applicative
import cats.data.{Kleisli, OptionT}
import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.reflect.ClassTag
import scala.reflect.runtime.universe.TypeTag

import org.tessellation.ext.kryo._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.gossip._
import org.tessellation.schema.peer.PeerId
import org.tessellation.syntax.boolean._

object RumorHandler {

  def fromCommonRumorConsumer[F[_]: Async: KryoSerializer, A <: AnyRef: TypeTag: ClassTag](
    f: CommonRumor[A] => F[Unit]
  ): RumorHandler[F] = {
    val handlerContentType = ContentType.of[A]
    val pf = new PartialFunction[(RumorBinary, PeerId), F[Unit]] {
      def isDefinedAt(v: (RumorBinary, PeerId)): Boolean = v match {
        case (rumor, _) =>
          rumor.isInstanceOf[CommonRumorBinary] &&
            rumor.contentType === handlerContentType
      }

      def apply(v: (RumorBinary, PeerId)): F[Unit] = v match {
        case (rumor: CommonRumorBinary, _) =>
          for {
            content <- rumor.content.fromBinaryF[A]
            fn = f(CommonRumor(content))
            _ <- fn
          } yield ()
        case (r, _) => UnexpectedRumorClass(r).raiseError[F, Unit]
      }
    }

    pfToKleisli(pf)
  }

  def fromPeerRumorConsumer[F[_]: Async: KryoSerializer, A <: AnyRef: TypeTag: ClassTag](
    selfOriginPolicy: OriginPolicy = IncludeSelfOrigin
  )(f: PeerRumor[A] => F[Unit]): RumorHandler[F] = {
    val handlerContentType = ContentType.of[A]
    val pf = new PartialFunction[(RumorBinary, PeerId), F[Unit]] {
      def isDefinedAt(v: (RumorBinary, PeerId)): Boolean = v match {
        case (rumor, selfId) =>
          rumor.isInstanceOf[PeerRumorBinary] &&
            rumor.contentType === handlerContentType &&
            ((rumor.asInstanceOf[PeerRumorBinary].origin === selfId) ==> (selfOriginPolicy =!= ExcludeSelfOrigin))
      }

      def apply(v: (RumorBinary, PeerId)): F[Unit] = v match {
        case (rumor: PeerRumorBinary, selfId) =>
          for {
            content <- rumor.content.fromBinaryF[A]
            fn = f(PeerRumor(rumor.origin, rumor.ordinal, content))
            _ <- if (rumor.origin === selfId && selfOriginPolicy === IgnoreSelfOrigin)
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
    pf: PartialFunction[(RumorBinary, PeerId), F[Unit]]
  ): Kleisli[OptionT[F, *], (RumorBinary, PeerId), Unit] =
    Kleisli.apply[OptionT[F, *], (RumorBinary, PeerId), Unit] {
      case (rumor, selfId) =>
        OptionT(pf.lift((rumor, selfId)).sequence)
    }

}
