package io.constellationnetwork.currency.dataApplication.ops

import cats.data.{NonEmptyList, Validated, ValidatedNec}
import cats.syntax.all._
import cats.{Applicative, MonadThrow}

import scala.collection.immutable.SortedSet
import scala.reflect.ClassTag

import io.constellationnetwork.currency.dataApplication.Errors.Noop
import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.context.L0NodeContext
import io.constellationnetwork.routes.internal.ExternalUrlPrefix
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import io.circe.{Decoder, Encoder}
import org.http4s.HttpRoutes

trait BaseDataApplicationL0ContextualOps[F[_]] extends BaseDataApplicationSharedContextualOps[F, L0NodeContext[F]] {

  def validateData(state: DataState.Base, updates: NonEmptyList[Signed[DataUpdate]])(
    implicit context: L0NodeContext[F]
  ): F[ValidatedNec[DataApplicationValidationError, Unit]]

  def combine(state: DataState.Base, updates: List[Signed[DataUpdate]])(implicit context: L0NodeContext[F]): F[DataState.Base]

  def getCalculatedState(implicit context: L0NodeContext[F]): F[(SnapshotOrdinal, DataCalculatedState)]

  def setCalculatedState(ordinal: SnapshotOrdinal, state: DataCalculatedState)(implicit context: L0NodeContext[F]): F[Boolean]

  def hashCalculatedState(state: DataCalculatedState)(implicit context: L0NodeContext[F]): F[Hash]

  def extractFees(ds: Seq[Signed[DataUpdate]])(implicit context: L0NodeContext[F], A: Applicative[F]): F[Seq[Signed[FeeTransaction]]] =
    A.pure(Seq.empty)

  def serializeCalculatedState(state: DataCalculatedState): F[Array[Byte]]
  def deserializeCalculatedState(bytes: Array[Byte]): F[Either[Throwable, DataCalculatedState]]

  def calculatedStateEncoder: Encoder[DataCalculatedState]
  def calculatedStateDecoder: Decoder[DataCalculatedState]
}

object BaseDataApplicationL0ContextualOps {
  def apply[F[_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState](
    service: DataApplicationL0ContextualOps[F, D, DON, DOF]
  )(
    implicit d: ClassTag[D],
    don: ClassTag[DON],
    dof: ClassTag[DOF],
    monadThrow: MonadThrow[F]
  ): BaseDataApplicationL0ContextualOps[F] =
    new BaseDataApplicationL0ContextualOps[F] {
      def allKnown(updates: List[Signed[DataUpdate]]): Boolean =
        updates.map(_.value).forall { case _: D => true; case _ => false }

      def validateData(state: DataState.Base, updates: NonEmptyList[Signed[DataUpdate]])(
        implicit context: L0NodeContext[F]
      ): F[ValidatedNec[DataApplicationValidationError, Unit]] =
        (state.onChain, state.calculated, state.sharedArtifacts) match {
          case (on: DON, off: DOF, sharedArtifacts: SortedSet[_]) if allKnown(updates.toList) =>
            service.validateData(
              DataState(on, off, sharedArtifacts.asInstanceOf[SortedSet[io.constellationnetwork.schema.artifact.SharedArtifact]]),
              updates.asInstanceOf[NonEmptyList[Signed[D]]]
            )
          case _ => Validated.invalidNec[DataApplicationValidationError, Unit](Noop).pure[F]
        }

      def combine(state: DataState.Base, updates: List[Signed[DataUpdate]])(
        implicit context: L0NodeContext[F]
      ): F[DataState.Base] =
        (state.onChain, state.calculated, state.sharedArtifacts) match {
          case (on: DON, off: DOF, sharedArtifacts: SortedSet[_]) if allKnown(updates) =>
            service
              .combine(
                DataState(on, off, sharedArtifacts),
                updates.asInstanceOf[List[Signed[D]]]
              )
              .map(_.asBase)
          case (_, _, _) => UnexpectedInput.raiseError[F, DataState.Base]
        }

      def getCalculatedState(implicit context: L0NodeContext[F]): F[(SnapshotOrdinal, DataCalculatedState)] =
        service.getCalculatedState.widen[(SnapshotOrdinal, DataCalculatedState)]

      def setCalculatedState(ordinal: SnapshotOrdinal, state: DataCalculatedState)(implicit context: L0NodeContext[F]): F[Boolean] =
        state match {
          case s: DOF => service.setCalculatedState(ordinal, s)
          case _      => UnexpectedInput.raiseError[F, Boolean]
        }

      def hashCalculatedState(state: DataCalculatedState)(implicit context: L0NodeContext[F]): F[Hash] =
        state match {
          case s: DOF => service.hashCalculatedState(s)
          case _      => UnexpectedInput.raiseError[F, Hash]
        }

      override def validateFee(
        gsOrdinal: SnapshotOrdinal
      )(dataUpdate: Signed[DataUpdate], maybeFeeTransaction: Option[Signed[FeeTransaction]])(
        implicit context: L0NodeContext[F],
        A: Applicative[F]
      ): F[ValidatedNec[DataApplicationValidationError, Unit]] =
        service.validateFee(gsOrdinal)(dataUpdate.asInstanceOf[Signed[D]], maybeFeeTransaction)

      override def extractFees(
        ds: Seq[Signed[DataUpdate]]
      )(implicit context: L0NodeContext[F], A: Applicative[F]): F[Seq[Signed[FeeTransaction]]] =
        service.extractFees(ds.asInstanceOf[Seq[Signed[D]]])

      def routes(implicit context: L0NodeContext[F]): HttpRoutes[F] = service.routes

      def routesPrefix: ExternalUrlPrefix = service.routesPrefix

      def serializeCalculatedState(state: DataCalculatedState): F[Array[Byte]] = service.serializeCalculatedState(state.asInstanceOf[DOF])

      def deserializeCalculatedState(bytes: Array[Byte]): F[Either[Throwable, DataCalculatedState]] =
        service.deserializeCalculatedState(bytes).widen

      def calculatedStateEncoder: Encoder[DataCalculatedState] = service.calculatedStateEncoder.asInstanceOf[Encoder[DataCalculatedState]]

      def calculatedStateDecoder: Decoder[DataCalculatedState] = service.calculatedStateDecoder.asInstanceOf[Decoder[DataCalculatedState]]
    }
}
