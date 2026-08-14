package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.Eq
import cats.effect.kernel.{Async, Ref}
import cats.syntax.all._

/** Local, generation-bound gate for the first consensus round after a deferred bootstrap/recovery initialization.
  *
  * The gate is armed before consensus/event daemons start. Binding it to the installed key creates a generation token. Only a serialized
  * release command carrying that exact `(key, generation)` may open it; a delayed release from an older rollback cannot open a newer hold.
  * While held, every ordinary start source (`StartRound`, `TimeTick`, and `FacilitateByEvent`) is dropped by the FSM.
  */
trait FirstRoundStartGate[F[_], Key] {
  def arm(key: Key): F[FirstRoundStartGate.Permit[Key]]
  def isHeld: F[Boolean]
  def isPending(permit: FirstRoundStartGate.Permit[Key]): F[Boolean]

  /** Establish the first round before opening the gate. The state transition itself is uncancelable, while the establishment effect remains
    * cancelable: failure or cancellation leaves the exact permit held and retryable.
    */
  def releaseAfter(permit: FirstRoundStartGate.Permit[Key])(scheduleFirstRound: F[Unit]): F[Boolean]
}

object FirstRoundStartGate {
  final case class Permit[Key](key: Key, generation: Long)

  private sealed trait State[+Key]
  private final case class Open(lastGeneration: Long) extends State[Nothing]
  private final case class Held[Key](generation: Long, key: Option[Key]) extends State[Key]

  private[consensus] def isOrdinaryStartCommand(command: ConsensusCommand[_, _, _, _]): Boolean =
    command match {
      case _: ConsensusCommand.StartRound | ConsensusCommand.TimeTick | ConsensusCommand.FacilitateByEvent => true
      case _                                                                                               => false
    }

  def make[F[_]: Async, Key: Eq](initiallyHeld: Boolean): F[FirstRoundStartGate[F, Key]] =
    Ref
      .of[F, State[Key]](if (initiallyHeld) Held(1L, none[Key]) else Open(0L))
      .map { state =>
        new FirstRoundStartGate[F, Key] {
          def arm(key: Key): F[Permit[Key]] =
            state.modify {
              case Open(lastGeneration) =>
                val next = Held(lastGeneration + 1L, key.some)
                next -> Permit(key, next.generation)
              case current @ Held(generation, Some(existing)) if existing === key =>
                current -> Permit(key, generation)
              case Held(generation, _) =>
                val next = Held(generation + 1L, key.some)
                next -> Permit(key, next.generation)
            }

          def isHeld: F[Boolean] = state.get.map {
            case Open(_)    => false
            case _: Held[_] => true
          }

          def isPending(permit: Permit[Key]): F[Boolean] = state.get.map {
            case Held(generation, Some(key)) => generation === permit.generation && key === permit.key
            case _                           => false
          }

          def releaseAfter(permit: Permit[Key])(scheduleFirstRound: F[Unit]): F[Boolean] =
            Async[F].uncancelable { poll =>
              state.get.flatMap {
                case Held(generation, Some(key)) if generation === permit.generation && key === permit.key =>
                  poll(scheduleFirstRound) >> state.modify {
                    case Held(currentGeneration, Some(currentKey))
                        if currentGeneration === permit.generation && currentKey === permit.key =>
                      Open(currentGeneration) -> true
                    case current => current -> false
                  }
                case _ => false.pure[F]
              }
            }
        }
      }
}
