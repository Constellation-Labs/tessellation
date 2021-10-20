package org.tesselation.infrastructure.gossip

import cats.data.{Kleisli, OptionT}
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.reflect.ClassTag
import scala.reflect.runtime.universe._

import org.tesselation.ext.kryo._
import org.tesselation.kryo.KryoSerializer
import org.tesselation.schema.gossip.{ReceivedRumor, Rumor}
import org.tesselation.schema.peer.PeerId

object RumorHandler {

  def fromReceivedRumorFn[F[_]: Async: KryoSerializer, A <: AnyRef: TypeTag: ClassTag](
    f: ReceivedRumor[A] => F[Unit]
  ): RumorHandler[F] = fromBiFn((id: PeerId, a: A) => f(ReceivedRumor(id, a)))

  def fromFn[F[_]: Async: KryoSerializer, A <: AnyRef: TypeTag: ClassTag](
    f: A => F[Unit]
  ): RumorHandler[F] = fromBiFn((_: PeerId, a: A) => f(a))

  def fromBiFn[F[_]: Async: KryoSerializer, A <: AnyRef: TypeTag: ClassTag](
    f: (PeerId, A) => F[Unit]
  ): RumorHandler[F] = {
    val tpe = typeOf[A].toString
    val pf = new PartialFunction[Rumor, F[Unit]] {
      override def isDefinedAt(rumor: Rumor): Boolean =
        rumor.tpe == tpe

      override def apply(rumor: Rumor): F[Unit] =
        for {
          a <- rumor.content.fromBinaryF[A]
          _ <- f(rumor.origin, a)
        } yield ()
    }
    Kleisli.apply[OptionT[F, *], Rumor, Unit](rumor => {
      OptionT(pf.lift(rumor).sequence)
    })
  }

}
