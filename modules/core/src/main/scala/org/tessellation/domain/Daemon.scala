package org.tessellation.domain

trait Daemon[F[_]] {
  def start: F[Unit]
}
