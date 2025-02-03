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
        events
          .flatMap(event => event.updateNodeParameters.proofs.toList.map(proof => (proof.id, event)))
          .sortWith {
            case ((id1, _), (id2, _)) =>
              def ordinalDiff(id: Id): Long = {
                val lastOrdinalValue = lastSnapshotUpdateNodeParameters
                  .get(id)
                  .map { case (_, snapshotOrdinal) => snapshotOrdinal }
                  .getOrElse(SnapshotOrdinal.MinValue)
                  .value
                currentOrdinalValue - lastOrdinalValue
              }

              ordinalDiff(id1) >= ordinalDiff(id2)
          }
          .take(maxUpdateNodeParameters)
          .map { case (_, event) => event }
          .distinct
          .pure[F]
      }
    }

}
