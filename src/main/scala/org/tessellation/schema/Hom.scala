package org.tessellation.schema

//todo put edge/signing stuff here
trait Hom[A, B] { self =>
//  val data: A = self.unit.data

//  val op: A => B

//  def observe = op(data)

  def tensor(x: Hom[A, _], y: Hom[_, B] = this): Hom[A, B] = unit// //https://ncatlab.org/nlab/show/Boardman-Vogt+tensor+product

  def unit: Hom[A, B] = this
}

object Hom {
  //todo use for lifts
  def ifEndo[A](g: A => A, pred: A => Boolean) : A => A = {
    a =>
      val newA = g(a)
      if (pred(newA)) newA else a
  }
}

case class Context(database: String)

abstract class Fiber[A, B] extends Hom[A, B]

abstract class Bundle[F, G](fibers: F) extends Fiber[F, G]

abstract class Simplex[T, U, V](fibers: Seq[Hom[T, U]]) extends Hom[U, V]
