package com.my.currency.l0

import org.tessellation.BuildInfo
import org.tessellation.currency.l0.CurrencyL0App
import org.tessellation.schema.cluster.ClusterId

import java.util.UUID

object Main
  extends CurrencyL0App(
    "custom-project-l0",
    "custom-project L0 node",
    ClusterId(UUID.fromString("517c3a05-9219-471b-a54c-21b7d72f4ae5")),
    tessellationVersion = BuildInfo.version,
    metagraphVersion = BuildInfo.version
  ) {}
