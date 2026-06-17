package io.constellationnetwork.kryo

import cats.effect.{Async, Resource}
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.{Applicative, MonadThrow}

import scala.reflect.ClassTag

import io.constellationnetwork.ext.kryo.KryoRegistrationId

import com.twitter.chill._

trait KryoSerializer[F[_]] {

  def serialize[A](data: A): Either[Throwable, Array[Byte]]

  def deserialize[T](bytes: Array[Byte])(implicit T: ClassTag[T]): Either[Throwable, T]

}

object KryoSerializer {

  private def kryoInstantiator(setReferences: Boolean): KryoInstantiator =
    new ScalaKryoInstantiator()
      .setRegistrationRequired(true)
      .setReferences(setReferences)

  def apply[F[_]: KryoSerializer]: KryoSerializer[F] = implicitly

  def make[F[_]: Async](registrar: Map[Class[_], Int], setReferences: Boolean): Resource[F, KryoPool] =
    Resource.make {
      Async[F].delay {
        KryoPool.withByteArrayOutputStream(
          10,
          kryoInstantiator(setReferences).withRegistrar(ExplicitKryoRegistrar(registrar))
        )
      }
    }(_ => Applicative[F].unit)

  def forAsync[F[_]: Async](
    registrar: Map[Class[_], KryoRegistrationId[_]],
    migrations: List[Migration[AnyRef, AnyRef]] = List.empty,
    setReferences: Boolean = false
  ): Resource[F, KryoSerializer[F]] =
    Resource.eval(validateRegistrarIdsAreUnique(registrar)).flatMap { _ =>
      make[F](registrar.view.mapValues(_.value).toMap, setReferences).map { kryoPool =>
        val migrationsMap = migrations.map(_.toPair).toMap
        new KryoSerializer[F] {
          def serialize[A](data: A): Either[Throwable, Array[Byte]] =
            Either.catchNonFatal {
              kryoPool.toBytesWithClass(data)
            }

          def deserialize[T](bytes: Array[Byte])(implicit T: ClassTag[T]): Either[Throwable, T] =
            try {
              val obj: AnyRef = kryoPool.fromBytes(bytes)
              val migration = migrationsMap.getOrElse((obj.getClass, T.runtimeClass), identity[AnyRef](_))
              val result = migration(obj)
              // Validate the deserialized type matches the expected type at runtime.
              // Without this check, JVM type erasure allows asInstanceOf[T] to silently succeed
              // for wrong types (e.g., Map$EmptyMap$ cast to Signed[GlobalSnapshot]), causing
              // ClassCastExceptions downstream when the value is actually used.
              if (!T.runtimeClass.isInstance(result))
                Left(
                  new ClassCastException(
                    s"Kryo deserialized ${result.getClass.getName} but expected ${T.runtimeClass.getName}"
                  )
                )
              else
                Right(result.asInstanceOf[T])
            } catch {
              case scala.util.control.NonFatal(t) => Left(t)
              // Kryo's reflection can throw LinkageError subclasses (InstantiationError,
              // NoClassDefFoundError, etc.) when speculative bytes don't match the registered
              // type's class shape — e.g., brotli bytes accidentally parsing as a registered
              // Kryo class ID whose constructor can't be invoked (scala.collection.immutable.Range).
              // These are recoverable deserialization failures, not JVM errors — treat them
              // the same as NonFatal so the caller can fall through to an alternate decoder.
              case le: LinkageError => Left(le)
            }
        }
      }
    }

  def validateRegistrarIdsAreUnique[F[_]: MonadThrow](registrar: Map[Class[_], KryoRegistrationId[_]]) = {
    val ids = registrar.view.values.toList
    if (ids.distinct.size != ids.size) new Throwable("Ids provided by registar are not unique.").raiseError[F, Unit]
    else Applicative[F].unit
  }
}
