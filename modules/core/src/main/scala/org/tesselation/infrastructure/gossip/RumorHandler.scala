package org.tesselation.infrastructure.gossip

import cats.data.{Kleisli, OptionT}
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.reflect.ClassTag
import scala.reflect.runtime.universe._

import org.tesselation.crypto.Signed
import org.tesselation.ext.kryo._
import org.tesselation.kryo.KryoSerializer
import org.tesselation.schema.ID.Id
import org.tesselation.schema.gossip.{ReceivedRumor, Rumor}

object RumorHandler {

  def fromReceivedRumorFn[F[_]: Async: KryoSerializer, A <: AnyRef: TypeTag: ClassTag](
    f: ReceivedRumor[A] => F[Unit]
  ): RumorHandler[F] = fromBiFn((id: Id, a: A) => f(ReceivedRumor(id, a)))

  def fromFn[F[_]: Async: KryoSerializer, A <: AnyRef: TypeTag: ClassTag](
    f: A => F[Unit]
  ): RumorHandler[F] = fromBiFn((_: Id, a: A) => f(a))

  def fromBiFn[F[_]: Async: KryoSerializer, A <: AnyRef: TypeTag: ClassTag](
    f: (Id, A) => F[Unit]
  ): RumorHandler[F] = {
    val tpe = typeOf[A].toString
    val pf = new PartialFunction[Signed[Rumor], F[Unit]] {
      override def isDefinedAt(signedRumor: Signed[Rumor]): Boolean =
        signedRumor.value.tpe == tpe

      override def apply(signedRumor: Signed[Rumor]): F[Unit] =
        for {
          a <- signedRumor.value.content.fromBinaryF[A]
          _ <- f(signedRumor.id, a)
        } yield ()
    }
    Kleisli.apply[OptionT[F, *], Signed[Rumor], Unit](rumor => {
      OptionT(pf.lift(rumor).sequence)
    })
  }

}
