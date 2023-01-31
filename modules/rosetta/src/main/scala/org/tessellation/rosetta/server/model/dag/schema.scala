package org.tessellation.rosetta.server.model.dag

/** Represents schema changes specific to this project related to metadata or type Any fields which do not properly code generate
  */
object schema {

  case class CallResponseActual()

  case class ErrorDetailKeyValue(name: String, value: String)

  case class ErrorDetails(details: List[ErrorDetailKeyValue])

}
