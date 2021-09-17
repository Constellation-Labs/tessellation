package org.tesselation.kryo

import cats.Applicative
import cats.effect.{Async, Resource}
import cats.syntax.either._

import com.twitter.chill._

trait KryoSerializer[F[_]] {

  def serialize(anyRef: AnyRef): Either[Throwable, Array[Byte]]

  def deserialize[T](bytes: Array[Byte]): Either[Throwable, T]
}

object KryoSerializer {

  private val kryoInstantiator: KryoInstantiator = new ScalaKryoInstantiator()
    .setRegistrationRequired(true)
    .setReferences(false)

  def apply[F[_]: KryoSerializer]: KryoSerializer[F] = implicitly

  def make[F[_]: Async](registrar: Map[Class[_], Int]): Resource[F, KryoPool] =
    Resource.make {
      Async[F].delay {
        KryoPool.withByteArrayOutputStream(
          10,
          kryoInstantiator.withRegistrar(ExplicitKryoRegistrar(registrar))
        )
      }
    }(_ => Applicative[F].unit)

  def forAsync[F[_]: Async](registrar: Map[Class[_], Int]): Resource[F, KryoSerializer[F]] = make[F](registrar).map {
    kryoPool =>
      new KryoSerializer[F] {
        def serialize(anyRef: AnyRef): Either[Throwable, Array[Byte]] =
          Either.catchNonFatal {
            kryoPool.toBytesWithClass(anyRef)
          }

        def deserialize[T](bytes: Array[Byte]): Either[Throwable, T] =
          Either.catchNonFatal {
            kryoPool.fromBytes(bytes).asInstanceOf[T]
          }
      }
  }
}
