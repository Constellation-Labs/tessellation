package org.tessellation.schema.http

import derevo.cats.show
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(decoder, encoder, show)
case class SuccessResponse[A](data: A)
