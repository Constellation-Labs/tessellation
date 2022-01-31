package org.tessellation.dag.block.config

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt

case class BlockValidatorConfig(requiredUniqueSigners: PosInt = 3)
