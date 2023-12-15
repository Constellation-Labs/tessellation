package org.tessellation.node.shared.domain.event

trait EventCutter[F[_], A, B] {
  def cut(aEvents: List[A], bEvents: List[B]): F[(List[A], List[B])]
}
