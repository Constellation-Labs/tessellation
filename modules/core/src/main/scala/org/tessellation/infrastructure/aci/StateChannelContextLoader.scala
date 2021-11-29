package org.tessellation.infrastructure.aci

import java.lang.reflect.Constructor
import java.net.URL
import java.util

import cats.effect.{Async, Resource}
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.traverse._

import scala.io.Source
import scala.reflect.ClassTag
import scala.util.control.NoStackTrace

import org.tessellation.domain.aci.{StateChannelManifest, StdCell}
import org.tessellation.ext.kryo._
import org.tessellation.infrastructure.aci.StateChannelContextLoader.KryoRegistrarIdNotPermitted
import org.tessellation.kernel.kryo.kernelKryoRegistrar
import org.tessellation.kernel.{HypergraphContext, StateChannelContext, Ω}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.kryo.schemaKryoRegistrar

import io.chrisdavenport.mapref.MapRef
import io.circe.parser._
import org.typelevel.log4cats.slf4j.Slf4jLogger

class StateChannelContextLoader[F[_]: Async] {

  val MANIFEST_FILE = "state-channel-manifest.json"
  val kryoIdPermittedAbove: Int = 1000
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
            .flatMap { manifest =>
              val valid = manifest.kryoRegistrar.forall {
                case (_, id) => id > kryoIdPermittedAbove
              }
              if (valid) manifest.pure[F] else KryoRegistrarIdNotPermitted.raiseError[F, StateChannelManifest]
            }
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
          cellClass.getConstructor(inputClass, classOf[HypergraphContext[F]])
        } catch {
          case _: NoSuchMethodException => cellClass.getConstructor(classOf[Ω], classOf[HypergraphContext[F]])
        }
      }

      val kryoRegistrar: Map[Class[_], Int] = manifest.kryoRegistrar.map {
        case (className, kryoId) =>
          (loader.loadClass(className), kryoId)
      } ++ schemaKryoRegistrar ++ kernelKryoRegistrar

      KryoSerializer.forAsync[F](kryoRegistrar).use { implicit serializer =>
        MapRef.ofConcurrentHashMap[F, Address, Balance]().map { balances =>
          new StateChannelContext[F] {
            override val address: Address = manifest.address

            override def createCell(inputBytes: Array[Byte], hypergraphContext: HypergraphContext[F]): F[StdCell[F]] =
              inputBytes.fromBinaryF[Ω](inputClassTag).map { input =>
                cellCtor.newInstance(input, hypergraphContext).asInstanceOf[StdCell[F]]
              }

            override def getBalance(address: Address): F[Balance] =
              balances(address).get.map(_.getOrElse(Balance.empty))
            override def setBalance(address: Address, balance: Balance): F[Unit] = balances(address).set(balance.some)
          }
        }
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

object StateChannelContextLoader {

  case object KryoRegistrarIdNotPermitted extends NoStackTrace

}
