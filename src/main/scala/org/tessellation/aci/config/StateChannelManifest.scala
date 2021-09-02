package org.tessellation.aci.config

case class StateChannelManifest(
  address: String,
  cellClass: String,
  inputClass: String,
  kryoRegistrar: Map[String, Int]
)
