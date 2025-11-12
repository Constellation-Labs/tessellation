package com.my.project_template.shared_data.serializers

import java.nio.charset.StandardCharsets

import io.constellationnetwork.currency.dataApplication.DataUpdate
import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationBlock
import io.constellationnetwork.security.signature.Signed

import com.my.project_template.shared_data.types.Types.{UsageUpdate, UsageUpdateCalculatedState, UsageUpdateState}
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, Printer}

object Serializers {
  private def serialize[A: Encoder](
    serializableData: A
  ): Array[Byte] = {
    def printer = Printer(dropNullValues = true, indent = "", sortKeys = true)
    serializableData.asJson.printWith(printer).getBytes(StandardCharsets.UTF_8)
  }

  def serializeUpdate(
    update: UsageUpdate
  ): Array[Byte] =
    serialize[UsageUpdate](update)

  def serializeState(
    state: UsageUpdateState
  ): Array[Byte] =
    serialize[UsageUpdateState](state)

  def serializeBlock(
    state: Signed[DataApplicationBlock]
  )(implicit e: Encoder[DataUpdate]): Array[Byte] =
    serialize[Signed[DataApplicationBlock]](state)

  def serializeCalculatedState(
    state: UsageUpdateCalculatedState
  ): Array[Byte] =
    serialize[UsageUpdateCalculatedState](state)
}
