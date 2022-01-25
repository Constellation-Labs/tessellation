package org.tessellation.ext

import scala.concurrent.duration.FiniteDuration

import _root_.cats.effect.Async
import _root_.cats.syntax.applicative._
import _root_.cats.syntax.flatMap._
import _root_.fs2.concurrent.{Signal, SignallingRef}
import _root_.fs2.{Pipe, Pull, Stream}

object fs2 {

  def pauseWhenInner[F[_]: Async, B](
    pauseWhenTrue: Signal[F, Boolean]
  ): Pipe[F, B, B] = { in =>
    def waitToResume =
      pauseWhenTrue.discrete
        .dropWhile(_ == true)
        .take(1)
        .compile
        .drain

    def pauseIfNeeded = Stream.exec {
      pauseWhenTrue.get.flatMap(paused => waitToResume.whenA(paused))
    }

    pauseIfNeeded ++ in.chunks.flatMap { chunk =>
      Stream.chunk(chunk) ++ pauseIfNeeded
    }
  }

  def switchRepeat[F[_]: Async, A](
    every: FiniteDuration,
    to: Stream[F, A]
  ): Pipe[F, A, A] = { in =>
    Stream.eval(SignallingRef.of[F, Boolean](true)).flatMap { paused =>
      val pause = Pull.eval(paused.set(true))
      val unpause = Pull.eval(paused.set(false))

      def go(p: Pull.Timed[F, A]): Pull[F, A, Unit] =
        p.timeout(every) >> p.uncons.flatMap {
          case Some((Right(elems), next)) =>
            pause >> Pull.output(elems) >> go(next)
          case Some((Left(_), next)) =>
            unpause >> go(next)
          case None =>
            Pull.done
        }

      val foreground = in.pull.timed(go).stream
      val background = to.through(pauseWhenInner(paused))

      foreground.mergeHaltL(background)
    }
  }

}
