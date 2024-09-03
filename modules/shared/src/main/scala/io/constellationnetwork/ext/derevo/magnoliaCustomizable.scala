package io.constellationnetwork.ext.derevo

import io.circe.magnolia.configured.Configuration

object magnoliaCustomizable {
  implicit val snakeCaseConfiguration: Configuration = Configuration.default.withSnakeCaseMemberNames
}
