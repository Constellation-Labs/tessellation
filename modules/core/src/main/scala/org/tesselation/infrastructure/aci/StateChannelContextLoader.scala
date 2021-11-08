package org.tesselation.infrastructure.aci

import java.lang.reflect.Constructor
import java.net.URL
import java.util

import cats.Applicative
import cats.effect.{Async, Resource}
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.io.Source
import scala.reflect.ClassTag

import org.tesselation.domain.aci.{StateChannelContext, StateChannelManifest, StdCell}
import org.tesselation.ext.kryo._
import org.tesselation.kernel.Ω
import org.tesselation.kryo.KryoSerializer
import org.tesselation.schema.address.Address

import io.circe.parser._
import org.typelevel.log4cats.slf4j.Slf4jLogger

class StateChannelContextLoader[F[_]: Async] {

  val MANIFEST_FILE = "state-channel-manifest.json"
  val classLoader: ClassLoader = this.getClass.getClassLoader

  private val logger = Slf4jLogger.getLogger[F]

  def loadFromClasspath: F[Map[Address, StateChannelContext[F]]] =
    for {
      urls <- Async[F].delay {
        classLoader.getResources(MANIFEST_FILE).toList
      }
      contexts <- urls.traverse { url =>
        readManifest(url).use { manifestStr =>
          parse(manifestStr)
            .flatMap(_.as[StateChannelManifest])
            .liftTo[F]
            .flatMap(createContext(classLoader, _))
        }
      }
      _ <- logger.info(s"${contexts.size} state channel(s) loaded")
    } yield contexts.map(ctx => ctx.address -> ctx).toMap

  private def readManifest(url: URL): Resource[F, String] =
    Resource
      .fromAutoCloseable(Async[F].delay {
        Source.fromURL(url)
      })
      .map(_.mkString)

  private def createContext(loader: ClassLoader, manifest: StateChannelManifest): F[StateChannelContext[F]] =
    Async[F].defer {
      val cellClass = loader.loadClass(manifest.cellClass)
      val inputClass = loader.loadClass(manifest.inputClass)
      val inputClassTag = ClassTag[Ω](inputClass)

      val cellCtor: Constructor[_] = {
        try {
          cellClass.getConstructor(inputClass)
        } catch {
          case e: NoSuchMethodException => cellClass.getConstructor(classOf[Ω])
        }
      }

      val kryoRegistrar: Map[Class[_], Int] = manifest.kryoRegistrar.map {
        case (className, kryoId) =>
          (loader.loadClass(className), kryoId)
      }

      KryoSerializer.forAsync[F](kryoRegistrar).use { implicit serializer =>
        Applicative[F].pure(new StateChannelContext[F] {
          override val address: Address = manifest.address

          override def createCell(inputBytes: Array[Byte]): F[StdCell[F]] =
            inputBytes.fromBinaryF[Ω](inputClassTag).map { input =>
              cellCtor.newInstance(input).asInstanceOf[StdCell[F]]
            }
        })
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
