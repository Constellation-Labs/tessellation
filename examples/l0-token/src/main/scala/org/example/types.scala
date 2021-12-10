package org.example

import org.tessellation.kernel.Ω

object types {

  case class L0TokenTransaction()

  sealed trait L0TokenStep extends Ω
  case class L0TokenBlock(transactions: Set[L0TokenTransaction]) extends L0TokenStep
  case class CreateStateChannelSnapshot() extends L0TokenStep
}
