package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.effect.IO
import cats.syntax.all._

import io.constellationnetwork.json.JsonSerializer

import io.circe.{Decoder, Encoder}

/** Shared orchestration for the v35 proof-sufficiency falsification tests.
  *
  * Layer suites supply only a production transition adapter from `(trusted prior outcome, public round frame)` to the next outcome and an
  * observation containing the bytes/facts that must agree. The harness executes the identical public frames through three lifecycles:
  *
  *   1. continuously warm state;
  *   2. canonical serialize/deserialize of the complete private outcome after every round; and
  *   3. fresh sequential replay from a canonically decoded independent root, without reading any intermediate private outcome.
  *
  * The third path is intentionally not allowed a sidecar callback. If a layer adapter cannot derive a field from its prior trusted outcome
  * plus the public frame, the test cannot manufacture it from node-local history.
  */
object CertifiedLineageReplayHarness {

  final case class Runs[Observation](
    warm: List[Observation],
    restartEveryRound: List[Observation],
    freshSequentialReplay: List[Observation]
  )

  private def roundTrip[A: Encoder: Decoder](value: A)(implicit serializer: JsonSerializer[IO]): IO[A] =
    serializer.serialize(value).flatMap(serializer.deserialize[A]).flatMap(IO.fromEither)

  private def run[State, Frame, Observation](
    root: State,
    frames: List[Frame],
    reloadBeforeEveryRound: Boolean,
    step: (State, Frame) => IO[(State, Observation)]
  )(implicit serializer: JsonSerializer[IO], stateEncoder: Encoder[State], stateDecoder: Decoder[State]): IO[List[Observation]] =
    frames
      .foldM((root, List.empty[Observation])) {
        case ((prior, observations), frame) =>
          prior
            .pure[IO]
            .flatMap(value => if (reloadBeforeEveryRound) roundTrip(value) else value.pure[IO])
            .flatMap(step(_, frame))
            .map { case (next, observation) => next -> (observations :+ observation) }
      }
      .map(_._2)

  def execute[State: Encoder: Decoder, Frame: Encoder: Decoder, Observation](
    root: State,
    frames: List[Frame]
  )(
    step: (State, Frame) => IO[(State, Observation)]
  )(implicit serializer: JsonSerializer[IO]): IO[Runs[Observation]] =
    for {
      warm <- run(root, frames, reloadBeforeEveryRound = false, step)
      restarted <- run(root, frames, reloadBeforeEveryRound = true, step)
      independentRoot <- roundTrip(root)
      publicFrames <- frames.traverse(roundTrip(_))
      replayed <- run(independentRoot, publicFrames, reloadBeforeEveryRound = false, step)
    } yield Runs(warm, restarted, replayed)
}
