package org.tessellation.node.shared.domain.snapshot.programs

import org.tessellation.security.HasherSelector

trait Download[F[_]] {
  def download(implicit hasherSelector: HasherSelector[F]): F[Unit]
}
