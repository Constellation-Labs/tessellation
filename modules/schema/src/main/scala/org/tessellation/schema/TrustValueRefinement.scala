package org.tessellation.schema

import eu.timepit.refined.numeric.Interval

object TrustValueRefinement {

  // Moved this here so that I can exclude this file from scalafmt. This is necessary because of a bug in the current
  // version of scalafmt that doesn't like the "-" in "-1.0", even though it is valid and the code compiles. scalafmt is
  // configured to ignore this file in the /{projectDir}/.scalafmt.conf configuration file.
  type TrustValueRefinement = Interval.Closed[-1.0, 1.0]
}
