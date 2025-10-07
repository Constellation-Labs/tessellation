package io.constellationnetwork.currency.dataApplication.services

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.block.DataApplicationBlock
import io.constellationnetwork.security.signature.Signed

import io.circe._
import org.http4s._

trait DataApplicationService[F[_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState] {
  def serializeState(state: DON): F[Array[Byte]]
  def deserializeState(bytes: Array[Byte]): F[Either[Throwable, DON]]

  def serializeUpdate(update: D): F[Array[Byte]]
  def deserializeUpdate(bytes: Array[Byte]): F[Either[Throwable, D]]

  def serializeBlock(block: Signed[DataApplicationBlock]): F[Array[Byte]]
  def deserializeBlock(bytes: Array[Byte]): F[Either[Throwable, Signed[DataApplicationBlock]]]

  def serializeCalculatedState(state: DOF): F[Array[Byte]]
  def deserializeCalculatedState(bytes: Array[Byte]): F[Either[Throwable, DOF]]

  def dataEncoder: Encoder[D]
  def dataDecoder: Decoder[D]

  def signedDataEntityDecoder: EntityDecoder[F, Signed[D]]

  def calculatedStateEncoder: Encoder[DOF]
  def calculatedStateDecoder: Decoder[DOF]
}
