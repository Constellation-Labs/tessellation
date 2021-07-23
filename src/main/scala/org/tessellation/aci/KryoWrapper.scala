package org.tessellation.aci

import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.syntax.all._
import com.twitter.chill.{KryoInstantiator, KryoPool, ScalaKryoInstantiator}

class KryoWrapper[F[_]](implicit F: Sync[F]) {

  case class KryoInfo(kryoPool: KryoPool, classes: Set[(Class[_], Int)])

  private val kryoInstantiator: KryoInstantiator = new ScalaKryoInstantiator()
    .setRegistrationRequired(true)
    .setReferences(false)

  private val kryoPoolWrapper: Ref[F, KryoInfo] = Ref.unsafe(
    KryoInfo(
      KryoPool.withByteArrayOutputStream(10, kryoInstantiator),
      Set.empty
    )
  )

  def registerClass(cls: Class[_], id: Int): F[Unit] = {
    kryoPoolWrapper
      .modify { ki =>
        val maybeClass = ki.classes.find { case (_, v) => v == id }.map(_._1)
        val maybeId = ki.classes.find { case (k, _)    => k == cls }.map(_._2)
        (maybeClass, maybeId) match {
          case (Some(existingClass), Some(existingId))
              if cls == existingClass && id == existingId =>
            (ki, none[String])
          case (_, Some(existingId)) if id != existingId =>
            (ki, s"Class already registered with id $existingId".some)
          case (Some(existingClass), _) if cls != existingClass =>
            (
              ki,
              s"Id already associated with class ${existingClass.getName}".some
            )
          case (None, None) =>
            val classes = ki.classes.+((cls, id))
            (
              KryoInfo(
                KryoPool.withByteArrayOutputStream(
                  10,
                  kryoInstantiator
                    .withRegistrar(ExplicitKryoRegistrar(classes))
                ),
                classes
              ),
              none[String]
            )
        }
      } >>= {
      case Some(m) => F.raiseError(new RuntimeException(m))
      case None    => F.unit
    }
  }

  def isAciTypeIdRegistered(id: Int): F[Boolean] = {
    kryoPoolWrapper.get.map { ki =>
      ki.classes.exists { case (_, someId) => id == someId }
    }
  }

  def serializeAnyRef(anyRef: AnyRef): F[Array[Byte]] =
    kryoPoolWrapper.get.map { ki =>
      ki.kryoPool.toBytesWithClass(anyRef)
    }

  def deserialize(bytes: Array[Byte]): F[AnyRef] =
    kryoPoolWrapper.get.map { ki =>
      ki.kryoPool.fromBytes(bytes)
    }

}
