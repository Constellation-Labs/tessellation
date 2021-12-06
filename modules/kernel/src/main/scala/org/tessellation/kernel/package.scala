package org.tessellation

import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Greater

package object kernel {
  type KryoRegistrationId = Int Refined Greater[1000]
}
