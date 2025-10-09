package io.constellationnetwork.currency.dataApplication.ops

import cats.Applicative
import cats.data.{NonEmptyList, ValidatedNec}
import cats.syntax.all._

import scala.collection.immutable.SortedSet

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.context.L0NodeContext
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.artifact.TokenUnlock
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import io.circe.{Decoder, Encoder}

trait DataApplicationL0ContextualOps[F[_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState]
    extends DataApplicationSharedContextualOps[F, D, DON, DOF, L0NodeContext[F]] {

  def validateData(state: DataState[DON, DOF], updates: NonEmptyList[Signed[D]])(
    implicit context: L0NodeContext[F]
  ): F[ValidatedNec[DataApplicationValidationError, Unit]]

  def combine(state: DataState[DON, DOF], updates: List[Signed[D]])(implicit context: L0NodeContext[F]): F[DataState[DON, DOF]]

  def getCalculatedState(implicit context: L0NodeContext[F]): F[(SnapshotOrdinal, DOF)]

  def setCalculatedState(ordinal: SnapshotOrdinal, state: DOF)(implicit context: L0NodeContext[F]): F[Boolean]

  def hashCalculatedState(state: DOF)(implicit context: L0NodeContext[F]): F[Hash]

  def hashDataUpdate: Option[D => F[Hash]] = None

  def extractFees(ds: Seq[Signed[D]])(implicit context: L0NodeContext[F], A: Applicative[F]): F[Seq[Signed[FeeTransaction]]] =
    A.pure(Seq.empty)

  def serializeCalculatedState(state: DOF): F[Array[Byte]]
  def deserializeCalculatedState(bytes: Array[Byte]): F[Either[Throwable, DOF]]

  def calculatedStateEncoder: Encoder[DOF]
  def calculatedStateDecoder: Decoder[DOF]
}
