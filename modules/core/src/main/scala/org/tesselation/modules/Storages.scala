package org.tesselation.modules

import cats.effect.kernel.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tesselation.domain.cluster.storage.{ClusterStorage, NodeStorage, SessionStorage}
import org.tesselation.infrastructure.cluster.storage.{ClusterStorage, SessionStorage}
import org.tesselation.infrastructure.node.NodeStorage

object Storages {

  def make[F[_]: Async]: F[Storages[F]] =
    for {
      clusterStorage <- ClusterStorage.make[F]
      nodeStorage <- NodeStorage.make[F]
      sessionStorage <- SessionStorage.make[F]
    } yield
      new Storages[F](
        cluster = clusterStorage,
        node = nodeStorage,
        session = sessionStorage
      ) {}
}

sealed abstract class Storages[F[_]] private (
  val cluster: ClusterStorage[F],
  val node: NodeStorage[F],
  val session: SessionStorage[F]
)
