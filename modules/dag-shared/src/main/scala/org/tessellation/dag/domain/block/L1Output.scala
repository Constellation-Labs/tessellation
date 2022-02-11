package org.tessellation.dag.domain.block

import org.tessellation.dag.domain.block.{DAGBlock, Tips}
import org.tessellation.security.signature.Signed

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(decoder, encoder)
case class L1Output(block: Signed[DAGBlock], tips: Tips)
