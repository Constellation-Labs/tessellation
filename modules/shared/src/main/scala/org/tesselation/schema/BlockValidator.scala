package org.tesselation.schema

import org.tesselation.security.signature.Signed

trait BlockValidator[F[_], A <: L1Block] {
  // TODO: Not sure if it won't become a signedBlocks: Seq[Signed[A]]
  def accept(signedBlock: Signed[A]): F[Unit]
}
