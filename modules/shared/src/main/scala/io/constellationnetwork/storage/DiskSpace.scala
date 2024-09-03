package io.constellationnetwork.storage

trait DiskSpace[F[_]] {
  def getUsableSpace: F[Long]

  def getOccupiedSpace: F[Long]
}
