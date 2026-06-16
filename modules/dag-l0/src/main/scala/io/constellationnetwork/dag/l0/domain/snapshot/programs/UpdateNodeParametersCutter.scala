package io.constellationnetwork.dag.l0.domain.snapshot.programs

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.dag.l0.infrastructure.snapshot.event.UpdateNodeParametersEvent
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.schema.{GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto.autoUnwrap
import eu.timepit.refined.types.numeric.PosInt

trait UpdateNodeParametersCutter[F[_]] {
  def cut(
    events: List[UpdateNodeParametersEvent],
    lastSnapshotContext: GlobalSnapshotInfo,
    ordinal: SnapshotOrdinal
  ): F[List[UpdateNodeParametersEvent]]
}

object UpdateNodeParametersCutter {

  def make[F[_]: Async](maxUpdateNodeParameters: PosInt): UpdateNodeParametersCutter[F] =
    new UpdateNodeParametersCutter[F] {
      def cut(
        events: List[UpdateNodeParametersEvent],
        lastSnapshotContext: GlobalSnapshotInfo,
        currentOrdinal: SnapshotOrdinal
      ): F[List[UpdateNodeParametersEvent]] = {
        val lastSnapshotUpdateNodeParameters = lastSnapshotContext.updateNodeParameters.getOrElse(
          SortedMap.empty[Id, (Signed[UpdateNodeParameters], SnapshotOrdinal)]
        )
        val currentOrdinalValue = currentOrdinal.value

        // Staleness rank of an id: ordinals elapsed since its parameters last changed (larger = staler).
        def ordinalDiff(id: Id): Long = {
          val lastOrdinalValue = lastSnapshotUpdateNodeParameters
            .get(id)
            .map { case (_, snapshotOrdinal) => snapshotOrdinal }
            .getOrElse(SnapshotOrdinal.MinValue)
            .value
          currentOrdinalValue - lastOrdinalValue
        }

        events
          .flatMap(event => event.updateNodeParameters.proofs.toList.map(proof => (proof, event)))
          // Deterministic TOTAL order: stalest first, then by the full signature proof to break ties. `events`
          // arrives from a Set (non-deterministic iteration) and `.take` below cuts at maxUpdateNodeParameters;
          // the previous `sortWith(_ >= _)` was a non-total order, and every first-time id shares the same
          // ordinalDiff (currentOrdinal - MinValue), so tied entries were kept in input order and different nodes
          // accepted different UNP subsets -> divergent GlobalSnapshotInfo -> divergent stateProof.mptRoot.
          // Tie-breaking on the whole proof (id + signature) stays total even when one signer has multiple
          // pending updates, so every node selects the identical subset regardless of input order.
          .sortBy { case (proof, _) => (-ordinalDiff(proof.id), proof) }
          .take(maxUpdateNodeParameters)
          .map { case (_, event) => event }
          .distinct
          .pure[F]
      }
    }

}
