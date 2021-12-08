package org.tessellation.schema

import org.tessellation.security.signature.Signed

trait BlockValidator[F[_], A <: L1Block] {
  def accept(signedBlock: Signed[A]): F[Unit]
}
