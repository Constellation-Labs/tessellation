package org.tesselation.modules

import cats.effect.kernel.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tesselation.domain.cluster.{ClusterStorage, NodeStorage}
import org.tesselation.infrastructure.cluster.ClusterStorage
import org.tesselation.infrastructure.node.NodeStorage

object Storages {

  def make[F[_]: Async]: F[Storages[F]] =
    for {
      clusterStorage <- ClusterStorage.make[F]
      nodeStorage <- NodeStorage.make[F]
    } yield
      new Storages[F](
        cluster = clusterStorage,
        node = nodeStorage
      ) {}
}

sealed abstract class Storages[F[_]] private (
  val cluster: ClusterStorage[F],
  val node: NodeStorage[F]
)
