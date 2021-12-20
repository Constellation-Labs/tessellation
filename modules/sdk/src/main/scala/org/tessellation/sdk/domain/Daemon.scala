package org.tessellation.sdk.domain

trait Daemon[F[_]] {
  def start: F[Unit]
}
