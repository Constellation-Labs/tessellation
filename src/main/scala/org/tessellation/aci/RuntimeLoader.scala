package org.tessellation.aci

import java.net.{URL, URLClassLoader}

import cats.syntax.all._
import cats.effect.{Resource, Sync}
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.tessellation.aci.config.StateChannelManifest
import pureconfig.ConfigSource

import scala.io.Source
import pureconfig.generic.auto._

class RuntimeLoader[F[_]](implicit F: Sync[F]) {

  private val logger = Slf4jLogger.getLogger[F]

  def loadRuntime(loader: ClassLoader, manifestUrl: URL): F[StateChannelRuntime] =
    for {
      manifestStr <- readManifest(manifestUrl)
      manifestObj <- loadManifest(manifestStr)
      runtime <- createRuntime(loader, manifestObj)
      _ <- logger.info(s"Runtime created for address ${runtime.address}")
    } yield runtime

  private def readManifest(url: URL): F[String] =
    Resource
      .fromAutoCloseable(F.delay {
        Source.fromURL(url)
      })
      .use { source =>
        F.delay {
          source.mkString
        }
      }

  private def loadManifest(manifestStr: String): F[StateChannelManifest] =
    ConfigSource.string(manifestStr).load[StateChannelManifest] match {
      case Right(scInfo) => F.pure(scInfo)
      case Left(value)   => F.raiseError[StateChannelManifest](new RuntimeException(value.prettyPrint()))
    }

  private def createRuntime(loader: ClassLoader, manifest: StateChannelManifest): F[StateChannelRuntime] = F.delay {
    val cellClass = loader.loadClass(manifest.cellClass)
    val inputClass = loader.loadClass(manifest.inputClass)
    val kryoRegistrar: Map[Class[_], Int] = manifest.kryoRegistrar.map {
      case (className, kryoId) => (loader.loadClass(className), kryoId)
    }

    new StateChannelRuntime(
      manifest.address,
      cellClass,
      inputClass,
      kryoRegistrar
    )
  }

}
