package org.tessellation.schema

import cats.free.Free
import cats.{Functor, Monad, Traverse, ~>}
import org.tessellation.schema.Enrichment.{A, M}

/**
  * Morphism object
  *
  * @param a
  * @param f
  * @tparam X
  */
case class Morphism[X](a: A, f: A => M[X]) {
  def apply: M[X] = f(a)
}

object Enrichment {
  type A // What we start with
  type B // What we end up with
  type T[_] // Our Traversable collection type
  type M[_] // Our Monad target type
  type FreeMorphism[X] = Free[Morphism, X]

  def lift[X](a: A, func: A => M[X]): FreeMorphism[X] =
    Free.liftF(Morphism(a, func))

  val transformation = new (Morphism ~> M) {
    override def apply[X](o: Morphism[X]): M[X] = o.apply
  }

  def toYoneda[F[_], A](fa: F[A])(implicit F: Functor[F]): Yoneda[F, A] =
    new Yoneda[F, A] {
      override def transformation[B](f: (A) => B): F[B] = F.map(fa)(f)
    }

  def fromYoneda[F[_], A](lf: Yoneda[F, A]): F[A] = lf.run

  implicit class TopEnrichedTraverse[F[_], A](as: F[A]) {

    def topologicalTraverse[B, M[_]](f: A => M[B])(implicit T: Traverse[F], MonadM: Monad[M]): M[F[B]] = {
      case class LazyFunction[X](a: A, f: A => M[X]) {
        def apply: M[X] = f(a)
      }
      type FreeLazyFunction[X] = Free[LazyFunction, X]

      def lift[X](a: A, func: A => M[X]): FreeLazyFunction[X] =
        Free.liftF(LazyFunction(a, func))

      val transformation = new (LazyFunction ~> M) {
        override def apply[X](o: LazyFunction[X]): M[X] = o.apply
      }
      T.traverse(as)(lift(_, f)) //todo organize top within lift
        .foldMap(transformation)
    }
  }

}

//https://ncatlab.org/nlab/show/Day+convolution Proposition 3.3
abstract class Yoneda[F[_], A] {
  self =>
  def transformation[B](f: A => B): F[B]

  def run: F[A] = transformation(identity)

  def map[B](f: A => B): Yoneda[F, B] = new Yoneda[F, B] {
    def transformation[C](g: (B) => C): F[C] = self.transformation(g.compose(f))
  }
}
