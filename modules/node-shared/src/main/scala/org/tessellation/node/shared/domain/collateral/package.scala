package org.tessellation.node.shared.domain

import scala.util.control.NoStackTrace

package object collateral {
  case object OwnCollateralNotSatisfied extends NoStackTrace
}
