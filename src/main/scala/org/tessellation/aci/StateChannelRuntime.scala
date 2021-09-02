package org.tessellation.aci

import java.lang.reflect.Constructor

import cats.syntax.all._
import cats.effect.Sync
import org.tessellation.schema.Ω

class StateChannelRuntime(
  val address: String,
  cellClass: Class[_],
  inputClass: Class[_],
  kryoRegistrar: Map[Class[_], Int]
) {

  private val cellCtor: Constructor[_] = {
    try {
      cellClass.getConstructor(inputClass)
    } catch {
      case e: NoSuchMethodException => cellClass.getConstructor(classOf[Ω])
    }
  }

  private val kryoPool = KryoFactory.createKryoInstance(kryoRegistrar)

  def createCell[F[_]](input: Ω): StdCell[F] =
    cellCtor.newInstance(input).asInstanceOf[StdCell[F]]

  def deserializeInput[F[_]](inputBytes: Array[Byte])(implicit F: Sync[F]): F[Ω] = // TODO change it to EitherT[F,Error,Ω]
    F.defer {
      val any = kryoPool.fromBytes(inputBytes)

      F.pure(inputClass.isAssignableFrom(any.getClass))
        .ifM(
          F.pure(any.asInstanceOf[Ω]),
          F.raiseError(new Throwable(s"input is not an instance of ${inputClass.getName}"))
        )
    }

  def deserializeCast[F[_], T](bytes: Array[Byte])(implicit F: Sync[F]): F[T] =
    F.delay {
      kryoPool.fromBytes(bytes).asInstanceOf[T]
    }

  def serialize[F[_]](any: Any)(implicit F: Sync[F]): F[Array[Byte]] =
    F.delay {
      kryoPool.toBytesWithClass(any)
    }
}
