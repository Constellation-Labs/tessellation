package org.tessellation.sdk.domain.snapshot.programs

trait Download[F[_]] {
  def download: F[Unit]
}
