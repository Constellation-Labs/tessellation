package org.tessellation.dag.domain.block

import cats.data.NonEmptyList

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder)
case class Tips(value: NonEmptyList[BlockReference])
