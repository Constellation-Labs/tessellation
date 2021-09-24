package org.tesselation.kryo

import cats.Applicative
import cats.effect.{Async, Resource}
import cats.syntax.either._

import scala.reflect.ClassTag

import com.twitter.chill._

trait KryoSerializer[F[_]] {

  def serialize(anyRef: AnyRef): Either[Throwable, Array[Byte]]

  def deserialize[T](bytes: Array[Byte])(implicit T: ClassTag[T]): Either[Throwable, T]

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

  def forAsync[F[_]: Async](
    registrar: Map[Class[_], Int],
    migrations: List[Migration[AnyRef, AnyRef]] = List.empty
  ): Resource[F, KryoSerializer[F]] = make[F](registrar).map { kryoPool =>
    val migrationsMap = migrations.map(_.toPair).toMap
    new KryoSerializer[F] {
      def serialize(anyRef: AnyRef): Either[Throwable, Array[Byte]] =
        Either.catchNonFatal {
          kryoPool.toBytesWithClass(anyRef)
        }

      def deserialize[T](bytes: Array[Byte])(implicit T: ClassTag[T]): Either[Throwable, T] =
        Either.catchNonFatal {
          val obj: AnyRef = kryoPool.fromBytes(bytes)
          val migration = migrationsMap.getOrElse((obj.getClass, T.runtimeClass), identity[AnyRef](_))
          migration(obj).asInstanceOf[T]
        }
    }
  }
}
