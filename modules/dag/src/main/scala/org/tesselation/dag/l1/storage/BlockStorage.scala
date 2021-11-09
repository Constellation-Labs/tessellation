package org.tesselation.dag.l1.storage

import cats.effect.Sync
import cats.effect.kernel.Ref
import cats.syntax.functor._

import org.tesselation.dag.l1.DAGBlock
import org.tesselation.dag.l1.storage.SetRefUtils._
import org.tesselation.security.Hashed

trait BlockStorage[F[_]] {
  def areParentsAccepted(block: DAGBlock): F[Boolean]
  def acceptBlock(hashedBlock: Hashed[DAGBlock]): F[Unit]
}

object BlockStorage {

  def make[F[_]: Sync](): BlockStorage[F] =
    new BlockStorage[F] {
      val accepted: Ref[F, Set[Hashed[DAGBlock]]] = Ref.unsafe(Set.empty)

      def areParentsAccepted(block: DAGBlock): F[Boolean] =
        accepted.get.map(acceptedBlocks => block.parent.forall(ref => acceptedBlocks.exists(_.hash == ref.hash)))

      def acceptBlock(hashedBlock: Hashed[DAGBlock]): F[Unit] =
        accepted.add(hashedBlock)
    }
}
