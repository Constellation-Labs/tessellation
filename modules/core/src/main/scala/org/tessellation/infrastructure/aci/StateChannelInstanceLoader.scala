package org.tessellation.infrastructure.aci

import java.net.URL
import java.util

import cats.Applicative
import cats.effect.{Async, Resource}
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.io.Source
import scala.reflect.runtime.universe

import org.tessellation.domain.aci.StateChannelInstance
import org.tessellation.kernel._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.shared.sharedKryoRegistrar

import org.typelevel.log4cats.slf4j.Slf4jLogger

class StateChannelInstanceLoader[F[_]: Async] {

  val defFile = "state-channel-def"
  val classLoader: ClassLoader = this.getClass.getClassLoader
  val runtimeMirror: universe.Mirror = universe.runtimeMirror(classLoader)

  private val logger = Slf4jLogger.getLogger[F]

  def loadFromClasspath: F[Map[Address, StateChannelInstance[F]]] =
    for {
      urls <- Async[F].delay {
        classLoader.getResources(defFile).toList
      }
      contexts <- urls.traverse { url =>
        readDefFile(url).use { loadDef(_) >>= createInstance }
      }
      _ <- logger.info(s"${contexts.size} state channel(s) loaded")
    } yield contexts.map(ctx => ctx.address -> ctx).toMap

  private def readDefFile(url: URL): Resource[F, String] =
    Resource
      .fromAutoCloseable(Async[F].delay {
        Source.fromURL(url)
      })
      .map(_.mkString)

  private def loadDef(defName: String): F[StateChannelDef[Ω, Ω, Ω]] =
    Async[F].delay {
      val module = runtimeMirror.staticModule(defName)
      val obj = runtimeMirror.reflectModule(module).instance
      obj.asInstanceOf[StateChannelDef[Ω, Ω, Ω]]
    }

  private def createInstance(stateChannelDef: StateChannelDef[Ω, Ω, Ω]): F[StateChannelInstance[F]] = {
    val kryoRegistrar: Map[Class[_], Int] = stateChannelDef.kryoRegistrar.view
      .mapValues(_.value)
      .toMap ++ sharedKryoRegistrar ++ kernelKryoRegistrar

    KryoSerializer.forAsync[F](kryoRegistrar).use { implicit serializer =>
      Applicative[F].pure(
        new StateChannelInstance[F] {
          val address = stateChannelDef.address
          val kryoSerializer = serializer

          def makeCell(input: Ω, hgContext: HypergraphContext[F]): Cell[F, StackF, Ω, Either[CellError, Ω], Ω] =
            stateChannelDef.makeCell(input, hgContext)

          def inputPipe = stateChannelDef.inputPipe
          def outputPipe = stateChannelDef.outputPipe
        }
      )
    }
  }
  implicit class EnumerationOps[T](enumeration: util.Enumeration[T]) {

    def toIterator: Iterator[T] = new Iterator[T] {
      override def hasNext: Boolean = enumeration.hasMoreElements

      override def next(): T = enumeration.nextElement()
    }

    def toList: List[T] = toIterator.toList
  }

}
