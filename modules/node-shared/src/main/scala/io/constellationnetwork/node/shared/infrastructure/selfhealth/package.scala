package io.constellationnetwork.node.shared.infrastructure

package object selfhealth {
  type SelfHealthHint = io.constellationnetwork.schema.consensus.SelfHealthHint
  val SelfHealthHint: io.constellationnetwork.schema.consensus.SelfHealthHint.type =
    io.constellationnetwork.schema.consensus.SelfHealthHint
}
