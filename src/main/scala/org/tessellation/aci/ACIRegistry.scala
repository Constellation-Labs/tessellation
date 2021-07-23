package org.tessellation.aci

import cats.data.OptionT
import cats.effect.{Async, ContextShift}
import cats.implicits._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.chrisdavenport.mapref.MapRef
import org.tessellation.aci.demo.ValidatedLike
import org.tessellation.schema.Ω
import org.tessellation.utils.MapRefUtils

import scala.reflect.runtime.universe._
import scala.tools.reflect.ToolBox

class ACIRegistry[F[_]](
  repository: ACIRepository[F],
  kryoWrapper: KryoWrapper[F]
)(implicit F: Async[F], C: ContextShift[F]) {

  private val logger = Slf4jLogger.getLogger[F].mapK(OptionT.liftK)

  private val typeCache: MapRef[F, String, Option[(Class[_], Symbol)]] =
    MapRefUtils.ofConcurrentHashMap()

  def findClassByAddress(address: String): OptionT[F, Class[_]] =
    findById(address).map(_._1)

  def findSymbolByAddress(address: String): OptionT[F, Symbol] =
    findById(address).map(_._2)

  // TODO this should be EitherT to return http 4xx on compilation error instead of 5xx
  def createACIType(address: String, definition: String): F[ACIType] =
    for {
      kryoRegistrationId <- repository.maxKryoRegistrationId
        .map(_ + 1)
        .getOrElse(0)
      aciType = ACIType(address, definition, kryoRegistrationId)
      _ <- compileAndRegisterType(aciType)
      _ <- repository.saveAciType(aciType)
    } yield (aciType)

  private def lookupInCache(address: String): OptionT[F, (Class[_], Symbol)] =
    OptionT(typeCache(address).get)

  private def findById(address: String): OptionT[F, (Class[_], Symbol)] =
    lookupInCache(address).orElse {
      for {
        aciType <- repository.findAciType(address)
        result <- OptionT.liftF(compileAndRegisterType(aciType))
      } yield result
    }

  private def compileAndRegisterType(
    aciType: ACIType
  ): F[(Class[_], Symbol)] =
    for {
      tup <- compileType(aciType.definition)
      (cls, sym) = tup
      _ <- kryoWrapper.registerClass(cls, aciType.kryoRegistrationId)
      _ <- typeCache(aciType.address).set(tup.some)
    } yield (cls, sym)

  private def compileType(definition: String): F[(Class[_], Symbol)] =
    F.defer {
      val tb = runtimeMirror(getClass.getClassLoader).mkToolBox()
      val typeDefTree: Tree = tb.parse(definition)
      val typeSym = tb.define(typeDefTree.asInstanceOf[ImplDef])
      val cls = tb.eval(q"classOf[$typeSym]").asInstanceOf[Class[_]]
      if (!classOf[Ω].isAssignableFrom(cls))
        F.raiseError(new Throwable(s"$cls is not a subclass of Ω"))
      else
        F.pure(cls, typeSym)
    }

}
