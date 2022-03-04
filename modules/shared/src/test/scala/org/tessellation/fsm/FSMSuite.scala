package org.tessellation.fsm

import cats.effect.IO
import cats.effect.kernel.Deferred
import cats.effect.std.Queue
import cats.syntax.contravariantSemigroupal._
import cats.syntax.option._

import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object FSMSuite extends SimpleIOSuite with Checkers {

  sealed trait State
  case object S1 extends State
  case object S2 extends State
  case object S3 extends State

  def mkFSM = FSM.make[IO, State](S1)
  def mkFSM(fn: (State => IO[Unit])) = FSM.make[IO, State](S1, fn)

  implicit class FSMOps(val fsm: FSM[IO, State]) {
    def expectState(s: State) = fsm.state.map(expect.same(_, s))
  }

  test("returns initial state") {
    mkFSM.flatMap { fsm =>
      fsm.expectState(S1)
    }
  }

  test("set - updates state") {
    mkFSM.flatMap { fsm =>
      fsm.set(S3) >>
        fsm.set(S1) >>
        fsm.set(S2) >>
        fsm.expectState(S2)
    }
  }

  test("set - should evaluate effect on changed state") {
    Deferred[IO, Int].flatMap { d =>
      mkFSM({
        case S2 => d.complete(1).void
        case _  => d.complete(2).void
      }).flatMap { fsm =>
        fsm.set(S2).flatMap(_ => d.tryGet).map {
          expect.same(_, 1.some)
        }
      }
    }
  }

  test("tryModify - changes state if state transition allowed") {
    mkFSM.flatMap { fsm =>
      fsm.tryModify(S1, S3) >>
        fsm.expectState(S3)
    }
  }

  test("tryModify - raises InvalidStateTransition if state transition not allowed") {
    mkFSM.flatMap { fsm =>
      fsm.set(S2) >>
        fsm.tryModify(S1, S3).attempt.map {
          case Left(FSM.InvalidStateTransition(_, _, _)) => success
          case _                                         => failure("should throw InvalidStateTransition")
        }
    }
  }

  test("tryModify - evaluates effect in between") {
    mkFSM.flatMap { fsm =>
      Deferred[IO, Int].flatMap { d =>
        fsm
          .tryModify(S1, S2, S3) {
            d.complete(1)
          }
          .flatMap { _ =>
            d.tryGet.map {
              case Some(1) => success
              case _       => failure("should evaluate effect")
            }
          }
      }
    }
  }

  test("tryModify - should change state to initial if effect raised an error") {
    mkFSM.flatMap { fsm =>
      fsm.tryModify(S1, S2, S3) { IO.raiseError[Unit](new Throwable("boom")) }.handleError(_ => ()) >>
        fsm.expectState(S1)
    }
  }

  test("tryModify - should evaluate effect on changed state") {
    Queue.unbounded[IO, Int].flatMap { q =>
      mkFSM({
        case S2 => q.offer(2)
        case S3 => q.offer(3)
        case _  => q.offer(0)
      }).flatMap { fsm =>
        fsm
          .tryModify(S1, S2, S3)(IO.unit)
          .flatMap { _ =>
            (q.tryTake, q.tryTake).mapN {
              case (a, b) => expect.all(a == 2.some, b == 3.some)
            }
          }
      }
    }
  }

  test("tryModify - should not allow for state transition in between another state transition") {
    mkFSM.flatMap { fsm =>
      Deferred[IO, Int].flatMap { d =>
        fsm
          .tryModify(S1, S2, S3) {
            fsm.tryModify(S1, S3).handleErrorWith {
              case FSM.InvalidStateTransition(_, _, _) => d.complete(1)
              case _                                   => IO.unit
            }
          }
          .flatMap(_ => d.tryGet)
          .map(expect.same(_, 1.some))

      }
    }
  }

  test("tryModify - allows for dependant state") {
    mkFSM.flatMap { fsm =>
      def output(a: Int) = if (a > 5) S3 else S2
      fsm.tryModify(S1, S2, { (r: Int) =>
        output(r)
      }) { IO.pure(6) } >>
        fsm.expectState(S3)
    }
  }
}
