package com.my.project_template.l1

import java.util.UUID
import io.constellationnetwork.BuildInfo
import io.constellationnetwork.currency.l1.CurrencyL1App
import io.constellationnetwork.schema.cluster.ClusterId
import io.constellationnetwork.schema.semver.{MetagraphVersion, TessellationVersion}

object Main extends CurrencyL1App(
  "currency-l1",
  "currency L1 node",
  ClusterId(UUID.fromString("517c3a05-9219-471b-a54c-21b7d72f4ae5")),
  tessellationVersion = TessellationVersion.unsafeFrom("1.0.0"),
  metagraphVersion = MetagraphVersion.unsafeFrom("1.0.0")
) {}
