package org.tessellation.node.shared.domain.fork

trait ForkDetect[F[_]] {
  def getMajorityFork: F[ForkInfo]
}
