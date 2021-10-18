package org.tesselation.modules

import cats.effect.kernel.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tesselation.domain.cluster.storage.{AddressStorage, ClusterStorage, SessionStorage}
import org.tesselation.domain.node.NodeStorage
import org.tesselation.infrastructure.cluster.storage.{AddressStorage, ClusterStorage, SessionStorage}
import org.tesselation.infrastructure.db.Database
import org.tesselation.infrastructure.node.NodeStorage

object Storages {

  def make[F[_]: Async: Database]: F[Storages[F]] =
    for {
      addressStorage <- AddressStorage.make[F]
      clusterStorage <- ClusterStorage.make[F]
      nodeStorage <- NodeStorage.make[F]
      sessionStorage <- SessionStorage.make[F]
    } yield
      new Storages[F](
        address = addressStorage,
        cluster = clusterStorage,
        node = nodeStorage,
        session = sessionStorage
      ) {}
}

sealed abstract class Storages[F[_]] private (
  val address: AddressStorage[F],
  val cluster: ClusterStorage[F],
  val node: NodeStorage[F],
  val session: SessionStorage[F]
)
