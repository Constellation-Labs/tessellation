package org.tesselation.domain

trait Daemon[F[_]] {
  def start: F[Unit]
}
