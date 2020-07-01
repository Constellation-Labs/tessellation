package org.tessellation.schema

import cats.{Applicative, Eval, Monoid, MonoidK, Traverse}
import higherkindness.droste.data.Attr
import higherkindness.droste.{CVAlgebra, Coalgebra}

//todo put edge/signing stuff here
trait Hom[A] { self =>
  val data: A = self.unit.data
  val coalgebra: Coalgebra[Hom, A] = Coalgebra(_ => unit)
  val algebra: CVAlgebra[Hom, A] = CVAlgebra {
    case h: Hom[Attr[Hom, A]] => unit.data
  }

//  def endo(x: Hom[_])(transformation: Hom[_] => Hom[_]): Hom[_]

  def tensor(x: Hom[_], y: Hom[_] = this): Hom[A] = unit// //https://ncatlab.org/nlab/show/Boardman-Vogt+tensor+product

  def unit: Hom[A] = this
}

object Hom {
  implicit val traverseInstance: Traverse[Hom] = new Traverse[Hom]{
    override def traverse[G[_], A, B](fa: Hom[A])(f: A => G[B])(implicit evidence$1: Applicative[G]): G[Hom[B]] = ???

    override def foldLeft[A, B](fa: Hom[A], b: B)(f: (B, A) => B): B = ???

    override def foldRight[A, B](fa: Hom[A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] = ???
  }

  //todo use for lifts
  def ifEndo[A](g: A => A, pred: A => Boolean) : A => A = {
    a =>
      val newA = g(a)
      if (pred(newA)) newA else a
  }
}

case class Context(database: String)

//todo think of as Semigroup ADT for joining data
abstract class Fiber[A] extends Hom[A]

//todo think of as Monoid ADT for Edges to be merged
abstract class Bundle[F](fibers: F) extends Fiber[F]

//todo think of as MonoidK ADT representations of an entire state dag converged (cell results)
abstract class Simplex[T](fibers: Seq[Bundle[T]]) extends Bundle[T](fibers.head.data)//todo fold/endo to get product
