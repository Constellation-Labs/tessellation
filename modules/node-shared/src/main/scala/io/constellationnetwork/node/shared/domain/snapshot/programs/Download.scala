package io.constellationnetwork.node.shared.domain.snapshot.programs

import io.constellationnetwork.security.HasherSelector

trait Download[F[_]] {
  def download(implicit hasherSelector: HasherSelector[F]): F[Unit]
}
