package org.tessellation.node.shared.domain.snapshot.programs

trait Download[F[_]] {
  def download: F[Unit]
}
