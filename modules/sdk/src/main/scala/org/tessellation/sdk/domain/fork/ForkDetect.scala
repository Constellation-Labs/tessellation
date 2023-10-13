package org.tessellation.sdk.domain.fork

trait ForkDetect[F[_]] {
  def getMajorityFork: F[ForkInfo]
}
