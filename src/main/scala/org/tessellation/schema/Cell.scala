package org.tessellation.schema

import cats.data.State
import cats.effect.{Concurrent, IO}
import cats.free.Free
import cats.implicits._
import cats.{Applicative, Bimonad, Eval, Functor, Monad, MonoidK, Traverse, ~>}
import shapeless.{:+:, CNil, Coproduct, HList, HNil, ProductTypeClass, TypeClass}

import scala.annotation.tailrec
import scala.collection.mutable


case class CellT[F[_] : Concurrent, A](value: A) {}

case class Cell[A, B](value: A) extends Hom[A, B]

case class Cocell[A, B](value: A, stateTransitionEval: B) extends Hom[A, B]