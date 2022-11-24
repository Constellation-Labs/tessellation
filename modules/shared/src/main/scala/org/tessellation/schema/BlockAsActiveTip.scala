package org.tessellation.schema

import org.tessellation.security.signature.Signed

import eu.timepit.refined.types.numeric.NonNegLong

trait BlockAsActiveTip[B <: Block[_]] {
  val block: Signed[B]
  val usageCount: NonNegLong
}
