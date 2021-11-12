package org.tessellation.ext.http4s

import org.tessellation.schema.address.{Address, DAGAddressRefined}

import eu.timepit.refined.refineV

object vars {

  object AddressVar {
    def unapply(str: String): Option[Address] = refineV[DAGAddressRefined](str).toOption.map(Address(_))
  }

}
