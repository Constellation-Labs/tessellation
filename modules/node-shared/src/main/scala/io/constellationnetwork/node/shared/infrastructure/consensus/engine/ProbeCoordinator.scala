package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.kernel.{Async, Ref}
import cats.effect.syntax.all._
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.infrastructure.consensus.engine.AbandonmentTracker.SuppressedBy

/** B3' probe scheduling coordinator: load control for the recovery-evidence probes, shared by both abandonment paths (retriable and
  * non-retriable), the locked-attempt path and the B1' isolation repair.
  *
  * Contract:
  *   - '''Atomic reservation.''' At most one evidence unit (peer rehabilitation pass + committed-ahead probe) runs at a time. A second
  *     caller that arrives while one is in flight is suppressed with `in_flight`; it never waits and never runs a second probe.
  *   - '''Completion-based cooldown.''' After a unit completes (successfully, with an error, or cancelled), the same scope may not run
  *     another unit until `cooldown` has elapsed since that completion. A different scope (new key or new resource generation) is not held
  *     by the old scope's cooldown.
  *   - '''Residence gate.''' A unit is eligible only once the key has been resident locally for at least `minResidence` (the
  *     time-trigger interval); the residence clock is D1's per-key first-seen instant, kept across same-key retries. An unknown residence
  *     (telemetry failure) is treated as eligible: this is load control, never a safety rule, and the probe itself is failure-safe.
  *   - '''Late results.''' When the unit completes, `scopeStillCurrent` is consulted; if the parent/generation moved on while the unit ran
  *     the result is returned as `Stale` and callers must treat it as no evidence.
  *
  * Eligibility here is separate from positive evidence (`EscalationSignal.decide`) and from transition/lock eligibility
  * (`shouldRecover`, `lockedAttemptAction`): a suppressed or stale unit only ever means "no probe evidence this cycle".
  */
final class ProbeCoordinator[F[_]: Async, Key](
  ref: Ref[F, ProbeCoordinator.State[Key]],
  now: F[FiniteDuration],
  minResidence: FiniteDuration,
  cooldown: FiniteDuration
) {
  import ProbeCoordinator._

  /** Read-only view of the eligibility decision `run` would make right now. */
  def eligibility(scope: Scope[Key], residence: Option[FiniteDuration]): F[Option[Suppression]] =
    (now, ref.get).tupled.map { case (at, state) => suppressionFor(state, scope, residence, at) }

  /** Reserve the coordinator for `scope`, run `unit`, release with a completion timestamp, then classify the result against
    * `scopeStillCurrent`. The reservation is released on every exit path.
    */
  def run[A](scope: Scope[Key], residence: Option[FiniteDuration], scopeStillCurrent: F[Boolean])(unit: F[A]): F[Result[A]] =
    now.flatMap { at =>
      ref.modify { state =>
        suppressionFor(state, scope, residence, at) match {
          case Some(suppression) => (state, suppression.asLeft[Unit])
          case None              => (state.copy(inFlight = scope.some), ().asRight[Suppression])
        }
      }
    }.flatMap {
      case Left(suppression) => (Result.Suppressed(suppression): Result[A]).pure[F]
      case Right(()) =>
        unit
          .guarantee(now.flatMap(done => ref.update(_.copy(inFlight = None, lastCompleted = (scope, done).some))))
          .flatMap(a => scopeStillCurrent.map(current => if (current) Result.Completed(a) else Result.Stale(a)))
    }

  def state: F[State[Key]] = ref.get

  private def suppressionFor(state: State[Key], scope: Scope[Key], residence: Option[FiniteDuration], at: FiniteDuration): Option[Suppression] =
    state.inFlight
      .map(_ => Suppression.InFlight: Suppression)
      .orElse(state.lastCompleted.collect {
        case (completedScope, completedAt) if completedScope == scope && at - completedAt < cooldown =>
          Suppression.Cooldown(cooldown - (at - completedAt))
      })
      .orElse(residence.collect { case r if r < minResidence => Suppression.Residence(minResidence - r) })
}

object ProbeCoordinator {

  /** The parent/generation an evidence unit is bound to: the abandoned key plus that key's resource generation. */
  final case class Scope[Key](key: Key, generation: Long)

  final case class State[Key](inFlight: Option[Scope[Key]], lastCompleted: Option[(Scope[Key], FiniteDuration)])

  object State {
    def empty[Key]: State[Key] = State(None, None)
  }

  /** Why the coordinator declined to run a unit. `Residence` is reported under D2's `cooldown` label (a cadence suppression: the key is
    * too young for a probe) with the distinguishing `detail` on the log line, keeping the metric label set bounded.
    */
  sealed trait Suppression {
    def suppressedBy: SuppressedBy
    def detail: String
  }

  object Suppression {
    case object InFlight extends Suppression {
      def suppressedBy: SuppressedBy = SuppressedBy.InFlight
      def detail: String = "in_flight"
    }

    final case class Cooldown(remaining: FiniteDuration) extends Suppression {
      def suppressedBy: SuppressedBy = SuppressedBy.Cooldown
      def detail: String = s"cooldown_remaining_ms=${remaining.toMillis}"
    }

    final case class Residence(remaining: FiniteDuration) extends Suppression {
      def suppressedBy: SuppressedBy = SuppressedBy.Cooldown
      def detail: String = s"residence_remaining_ms=${remaining.toMillis}"
    }
  }

  sealed trait Result[+A]

  object Result {
    final case class Completed[A](value: A) extends Result[A]
    final case class Stale[A](discarded: A) extends Result[A]
    final case class Suppressed(suppression: Suppression) extends Result[Nothing]
  }

  def make[F[_]: Async, Key](now: F[FiniteDuration], minResidence: FiniteDuration, cooldown: FiniteDuration): F[ProbeCoordinator[F, Key]] =
    Ref.of[F, State[Key]](State.empty[Key]).map(new ProbeCoordinator[F, Key](_, now, minResidence, cooldown))

  def unsafe[F[_]: Async, Key](minResidence: FiniteDuration, cooldown: FiniteDuration): ProbeCoordinator[F, Key] =
    new ProbeCoordinator[F, Key](Ref.unsafe(State.empty[Key]), Async[F].monotonic, minResidence, cooldown)
}
