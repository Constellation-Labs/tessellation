package org.tessellation.ext.cats

import org.tessellation.ext.cats.effect.Lock._
import org.tessellation.ext.cats.effect.OptionLock._

import _root_.cats.Eq
import _root_.cats.effect.kernel.Async
import _root_.cats.effect.syntax.all._
import _root_.cats.effect.{IO, Ref, Resource}
import _root_.cats.syntax.all._

package object effect {
  implicit class ResourceIO[A](value: IO[A]) {
    def asResource: Resource[IO, A] = Resource.eval { value }
  }

  implicit class RefOps[F[_]: Async, A: Eq](r: Ref[F, A]) {

    def condTryModify[B](f: A => Option[(A, B)]): F[Option[B]] =
      for {
        (a, set) <- r.access
        mb <- f(a).flatTraverse {
          case (u, b) => set(u).map { if (_) b.some else none }
        }
      } yield mb

    def tryCompareAndSet(e: A, u: A): F[Boolean] =
      for {
        (a, set) <- r.access
        b <- if (a === e)
          set(u)
        else
          false.pure[F]
      } yield b

  }

  implicit class RefOptionLockOps[F[_]: Async, A: Eq](lockR: Ref[F, Option[OptionLock[A]]]) {

    def getL: F[Option[A]] = lockR.get.map {
      case Some(lock) =>
        lock match {
          case NoneAcquired()  => none
          case SomeAcquired(a) => a.some
          case SomeReleased(a) => a.some
        }
      case None => none
    }

    def condTryModifyL[B](f: Option[A] => F[Option[(Option[A], B)]]): F[Option[B]] =
      lockR.condTryModify {
        case Some(lock) =>
          lock match {
            case NoneAcquired()  => none
            case SomeAcquired(_) => none
            case SomeReleased(a) => (someAcquired(a).some, a.some).some
          }
        case None => (noneAcquired[A].some, none).some
      }.bracket {
        _.flatTraverse { ma =>
          f(ma).flatMap {
            _.flatTraverse {
              case (mu, b) =>
                lockR
                  .tryCompareAndSet(
                    ma.fold(noneAcquired[A])(someAcquired).some,
                    mu.map(someReleased)
                  )
                  .ifM(b.some.pure[F], none[B].pure[F])
            }
          }
        }
      } {
        _.traverse { ma =>
          lockR.tryCompareAndSet(
            ma.fold(noneAcquired[A])(someAcquired).some,
            ma.map(someReleased)
          )
        }.void
      }

    def tryModifyL[B](f: Option[A] => F[(Option[A], B)]): F[Option[B]] =
      condTryModifyL(ma => f(ma).map(_.some))
  }

  implicit class RefLockOps[F[_]: Async, A: Eq](lockR: Ref[F, Lock[A]]) {

    def getL: F[A] = lockR.get.map {
      case Acquired(a) => a
      case Released(a) => a
    }

    def condTryModifyL[B](f: A => F[Option[(A, B)]]): F[Option[B]] =
      lockR.condTryModify {
        case Acquired(_) => none
        case Released(a) => (acquired(a), a).some
      }.bracket {
        _.flatTraverse { a =>
          f(a).flatMap {
            _.flatTraverse {
              case (u, b) =>
                lockR
                  .tryCompareAndSet(acquired(a), released(u))
                  .ifM(b.some.pure[F], none[B].pure[F])
            }
          }
        }
      } {
        _.traverse { a =>
          lockR.tryCompareAndSet(acquired(a), released(a))
        }.void
      }

    def tryModifyL[B](f: A => F[(A, B)]): F[Option[B]] =
      condTryModifyL(a => f(a).map(_.some))

    def tryUpdateL(f: A => F[A]): F[Boolean] =
      tryModifyL(a => f(a).map(_ -> ())).map(_.isDefined)

  }
}
