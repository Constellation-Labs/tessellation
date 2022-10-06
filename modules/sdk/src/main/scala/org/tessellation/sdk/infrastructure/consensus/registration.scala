package org.tessellation.sdk.infrastructure.consensus

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

object registration {

  @derive(encoder, decoder)
  case class RegistrationResponse[Key](
    maybeKey: Option[Key]
  )

  @derive(encoder, decoder)
  case class Deregistration[Key](key: Key)

}
