package org.tessellation.sdk.infrastructure.consensus

case class RegistrationExchangeRequest[Key](
  maybeKey: Option[Key]
)

case class RegistrationExchangeResponse[Key](
  maybeKey: Option[Key]
)

case class Deregistration[Key](key: Key)
