package org.tessellation.aci

import java.util.{Enumeration => JEnumeration}

import cats.effect.Sync
import cats.syntax.all._
import fs2.Stream
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.tessellation.utils.streamLiftK

class ClasspathScanner[F[_]](runtimeLoader: RuntimeLoader[F])(implicit F: Sync[F]) {

  private val logger = Slf4jLogger.getLogger[F].mapK(streamLiftK)

  val MANIFEST_FILE = "state-channel.info"
  val classLoader: ClassLoader = this.getClass.getClassLoader

  def scanClasspath: Stream[F, Map[String, StateChannelRuntime]] =
    for {
      result <- Stream
        .fromIterator[F](classLoader.getResources(MANIFEST_FILE).toIterator)
        .evalMap(url => runtimeLoader.loadRuntime(classLoader, url))
        .map(runtime => Map(runtime.address -> runtime))
        .fold(Map.empty[String, StateChannelRuntime])(_ <+> _)
      _ <- logger.info(s"${result.size} state channel(s) loaded")
    } yield result

  implicit class EnumerationOps[T](enumeration: JEnumeration[T]) {

    def toIterator: Iterator[T] = new Iterator[T] {
      override def hasNext: Boolean = enumeration.hasMoreElements

      override def next(): T = enumeration.nextElement()
    }
  }

}
