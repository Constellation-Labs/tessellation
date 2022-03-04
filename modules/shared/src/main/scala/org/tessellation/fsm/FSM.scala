package org.tessellation.fsm

import cats.effect.Ref
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, MonadThrow}

import scala.util.control.NoStackTrace

import derevo.cats.{eqv, show}
import derevo.derive

trait FSM[F[_], S] {
  def state: F[S]

  def set(to: S): F[Unit]

  def tryModify[R](from: Set[S], through: S, to: R => S)(fn: => F[R]): F[R]

  def tryModify(from: Set[S], to: S): F[Unit]


  def tryModify[R](from: S, through: S, to: S)(fn: => F[R]): F[R] =
    tryModify(Set(from), through, to)(fn)

  def tryModify[R](from: S, through: S, to: R => S)(fn: => F[R]): F[R] = 
    tryModify(Set(from), through, to)(fn)

  def tryModify[R](from: Set[S], through: S, to: S)(fn: => F[R]): F[R] =
    tryModify(from, through, {(_: R) => to})(fn)
  def tryModify(from: S, to: S): F[Unit] =
    tryModify(Set(from), to)
}

object FSM {

  def make[F[_]: MonadThrow: Ref.Make, S](initial: S): F[FSM[F, S]] =
    make(initial, _ => Applicative[F].unit)

  def make[F[_]: MonadThrow: Ref.Make, S](initial: S, onChange: S => F[Unit]): F[FSM[F, S]] =
    Ref.of[F, S](initial).map { stateR =>
      new FSM[F, S] {
        def state = stateR.get

        def set(to: S) = stateR.set(to) >> onChange(to)

        def tryModify[R](from: Set[S], through: S, to: R => S)(fn: => F[R]): F[R] = 
          state.flatMap { initial =>
            modify(from, through).flatMap {
              case StateTransition.Failure => InvalidStateTransition(initial, from, through).raiseError[F, R]
              case StateTransition.Success =>
                fn.flatMap { r =>
                  modify(Set(through), to(r)).flatMap {
                    case StateTransition.Failure =>
                      state.flatMap { InvalidStateTransition(_, Set(through), to(r)).raiseError[F, R] }
                    case StateTransition.Success => r.pure[F]
                  }
                }.handleErrorWith { error =>
                  modify(Set(through), initial) >> error.raiseError[F, R]
                }
            }
          } 

        def tryModify(from: Set[S], to: S): F[Unit] =
          state.flatMap { initial =>
            modify(from, to).flatMap {
              case StateTransition.Failure => InvalidStateTransition[S](initial, from, to).raiseError[F, Unit]
              case StateTransition.Success => Applicative[F].unit
            }
          }

        private def modify(from: Set[S], to: S): F[StateTransition] =
          stateR
            .modify[StateTransition] {
              case state if from.contains(state) => (to, StateTransition.Success)
              case state                         => (state, StateTransition.Failure)
            }
            .flatTap {
              case StateTransition.Success => onChange(to)
              case _                       => Applicative[F].unit
            }
      }
    }

  @derive(eqv, show)
  sealed trait StateTransition

  object StateTransition {
    case object Success extends StateTransition
    case object Failure extends StateTransition
  }

  case class InvalidStateTransition[S](initial: S, from: Set[S], to: S) extends NoStackTrace
}
