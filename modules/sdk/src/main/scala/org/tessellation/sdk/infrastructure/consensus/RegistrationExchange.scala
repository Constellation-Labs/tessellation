package org.tessellation.sdk.infrastructure.consensus

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder)
case class RegistrationExchangeRequest[Key](
  maybeKey: Option[Key]
)

@derive(encoder, decoder)
case class RegistrationExchangeResponse[Key](
  maybeKey: Option[Key]
)

@derive(encoder, decoder)
case class Deregistration[Key](key: Key)
