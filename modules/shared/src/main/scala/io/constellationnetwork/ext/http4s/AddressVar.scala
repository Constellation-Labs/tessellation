package io.constellationnetwork.ext.http4s

import io.constellationnetwork.schema.address.{Address, DAGAddressRefined}

import eu.timepit.refined.refineV

object AddressVar {
  def unapply(str: String): Option[Address] = refineV[DAGAddressRefined](str).toOption.map(Address(_))
}
