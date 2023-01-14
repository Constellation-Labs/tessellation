package org.tessellation.rosetta.server.model.dag

import org.tessellation.rosetta.server.model.dag.enums.ChainObjectStatus

/** Represents schema changes specific to this project related to metadata or type Any fields which do not properly code generate
  */
object schema {

  case class NetworkChainObjectStatus(value: ChainObjectStatus)

  case class CallResponseActual()

  case class ConstructionMetadataRequestOptions()

  case class ErrorDetailKeyValue(name: String, value: String)

  case class ErrorDetails(details: List[ErrorDetailKeyValue])

}
