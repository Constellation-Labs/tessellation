package org.tessellation.snapshot

import cats.{Applicative, Traverse}
import cats.syntax.all._
import higherkindness.droste.util.DefaultTraverse
import org.tessellation.consensus.L1Block
import org.tessellation.schema.{Hom, Ω}

case class L0Edge[A](blocks: Set[L1Block]) extends Ω

case class Snapshot(blocks: Set[L1Block]) extends Ω

trait L0SnapshotF[A] extends Hom[Ω, A]

case class CreateSnapshot[A](edge: L0Edge[Snapshot]) extends L0SnapshotF[A]

case class SnapshotEnd[A](blocks: Set[L1Block]) extends L0SnapshotF[A]

case class L0Error[A](reason: String) extends L0SnapshotF[A]

object L0SnapshotF {
  implicit val traverse: Traverse[L0SnapshotF] = new DefaultTraverse[L0SnapshotF] {
    override def traverse[G[_]: Applicative, A, B](fa: L0SnapshotF[A])(f: A => G[B]): G[L0SnapshotF[B]] =
      fa.asInstanceOf[L0SnapshotF[B]].pure[G]
  }
}
