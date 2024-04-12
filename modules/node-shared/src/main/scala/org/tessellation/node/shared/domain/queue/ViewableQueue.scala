package org.tessellation.node.shared.domain.queue

import cats.effect.Ref
import cats.effect.kernel.Concurrent
import cats.syntax.functor._

import scala.collection.immutable.Queue

trait ViewableQueue[F[_], A] {
  def offer(item: A): F[Unit]

  def take: F[A]

  def tryOfferN(items: List[A]): F[List[A]]

  def tryTakeN(n: Option[Int]): F[List[A]]

  def view: F[List[A]]
}

object ViewableQueue {

  def make[F[_]: Concurrent, A]: F[ViewableQueue[F, A]] =
    Ref[F].of(Queue.empty[A]).map { queue =>
      new ViewableQueue[F, A] {
        def offer(item: A): F[Unit] =
          queue.update(_.enqueue(item))

        def take: F[A] =
          queue.modify { q =>
            val (head, tail) = q.dequeue
            (tail, head)
          }

        def tryOfferN(items: List[A]): F[List[A]] =
          queue.update(_ ++ items).as(List.empty[A])

        def tryTakeN(n: Option[Int]): F[List[A]] =
          queue.modify { q =>
            val (taken, remaining) = q.splitAt(n.getOrElse(0))
            (remaining, taken.toList)
          }

        def view: F[List[A]] =
          queue.get.map(_.toList)
      }
    }
}
