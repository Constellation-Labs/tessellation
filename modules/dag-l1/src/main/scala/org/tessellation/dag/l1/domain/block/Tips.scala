package org.tessellation.dag.l1.domain.block

import cats.data.NonEmptyList

import org.tessellation.dag.domain.block.BlockReference

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder)
case class Tips(value: NonEmptyList[BlockReference])
