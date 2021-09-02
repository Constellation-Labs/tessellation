package org.tessellation.aci

import java.io.File
import java.net.{URL, URLClassLoader}
import java.nio.file.Files

import cats.data.OptionT
import cats.effect.{Async, ContextShift}
import cats.implicits._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.chrisdavenport.mapref.MapRef

class ACIRegistry[F[_]](
  repository: ACIRepository[F],
  runtimeLoader: RuntimeLoader[F],
  runtimeCache: MapRef[F, String, Option[StateChannelRuntime]]
)(implicit F: Async[F], C: ContextShift[F]) {

  private val logger = Slf4jLogger.getLogger[F]

  def createStateChannelJar(content: Array[Byte]): F[StateChannelJar] =
    for {
      runtime <- loadStateChannelJar(content)
      _ <- runtimeCache(runtime.address).set(runtime.some)
      jar = StateChannelJar(runtime.address, content)
      _ <- repository.saveStateChannelJar(jar)
      _ <- logger.info(s"Saved ${content.length} under ${jar.id}")
    } yield jar

  def getStateChannelRuntime(address: String): OptionT[F, StateChannelRuntime] =
    OptionT(runtimeCache(address).get).orElse {
      for {
        jar <- repository.findStateChannelJar(address)
        runtime <- OptionT.liftF(loadStateChannelJar(jar.content))
        // TODO assert address == runtime.address
        _ <- OptionT.liftF(runtimeCache(runtime.address).set(runtime.some))
      } yield runtime
    }

  private def loadStateChannelJar(content: Array[Byte]): F[StateChannelRuntime] =
    F.defer {
      for {
        jarFile <- saveJarToFile(content)
        loader = URLClassLoader.newInstance(Array(jarFile.toURI.toURL), this.getClass.getClassLoader)
        manifestUrl <- findManifestUrl(loader)
        runtime <- runtimeLoader.loadRuntime(loader, manifestUrl)
      } yield runtime
    }

  def saveJarToFile(content: Array[Byte]): F[File] = F.delay {
    val tempFile = File.createTempFile("state-channel", "jar")
    Files.write(tempFile.toPath, content)
    tempFile.deleteOnExit()
    tempFile
  }

  def findManifestUrl(loader: URLClassLoader): F[URL] =
    OptionT {
      F.delay { Option(loader.getResource("state-channel.info")) }
    }.getOrElseF(F.raiseError(new RuntimeException("state-channel.info not found")))

}
