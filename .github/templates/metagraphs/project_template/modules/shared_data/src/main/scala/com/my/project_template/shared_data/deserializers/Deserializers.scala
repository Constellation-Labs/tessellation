package com.my.project_template.shared_data.deserializers

import java.nio.charset.StandardCharsets

import io.constellationnetwork.currency.dataApplication.DataUpdate
import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationBlock
import io.constellationnetwork.security.signature.Signed

import com.my.project_template.shared_data.types.Types.{UsageUpdate, UsageUpdateCalculatedState, UsageUpdateState}
import io.circe.Decoder
import io.circe.jawn.decode

object Deserializers {
  private def deserialize[A: Decoder](
    bytes: Array[Byte]
  ): Either[Throwable, A] =
    decode[A](new String(bytes, StandardCharsets.UTF_8))

  def deserializeUpdate(
    bytes: Array[Byte]
  ): Either[Throwable, UsageUpdate] =
    deserialize[UsageUpdate](bytes)

  def deserializeState(
    bytes: Array[Byte]
  ): Either[Throwable, UsageUpdateState] =
    deserialize[UsageUpdateState](bytes)

  def deserializeBlock(
    bytes: Array[Byte]
  )(implicit e: Decoder[DataUpdate]): Either[Throwable, Signed[DataApplicationBlock]] =
    deserialize[Signed[DataApplicationBlock]](bytes)

  def deserializeCalculatedState(
    bytes: Array[Byte]
  ): Either[Throwable, UsageUpdateCalculatedState] =
    deserialize[UsageUpdateCalculatedState](bytes)
}
