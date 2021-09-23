package org.tesselation.kryo

import com.esotericsoftware.kryo.serializers.VersionFieldSerializer.Since

case class NoChangesV1(
  amount: Long,
  address: String
)

case class NonBreakingChangesV2(
  amount: Long,
  address: String,
  remark: String
)

case class BreakingChangesClassV2(
  amount: Long,
  remark: String
)
