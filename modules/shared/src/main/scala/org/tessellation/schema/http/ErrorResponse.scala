package org.tessellation.schema.http

import cats.data.NonEmptyList

import derevo.cats.show
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(decoder, encoder, show)
case class ErrorResponse(errors: NonEmptyList[ErrorCause])
