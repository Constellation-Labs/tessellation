package io.constellationnetwork.storage

import derevo.cats.show
import derevo.derive
import io.estatico.newtype.macros.newtype

abstract class PathGenerator {
  def get(filename: String): String
}

object PathGenerator {
  def flat: PathGenerator = new PathGenerator {
    def get(filename: String): String = filename
  }

  @derive(show)
  @newtype
  case class Depth(value: Int)

  @derive(show)
  @newtype
  case class PrefixSize(value: Int)

  def forHash(depth: Depth, prefixSize: PrefixSize): PathGenerator = new PathGenerator {
    def get(filename: String): String =
      filename
        .grouped(prefixSize.value)
        .take(depth.value)
        .toList
        .mkString("/")
        .concat(s"/$filename")
  }

  @newtype
  case class ChunkSize(value: Int)

  def forOrdinal(chunkSize: ChunkSize): PathGenerator = new PathGenerator {
    def get(filename: String): String = {
      val chunkIndex = filename.toLong / chunkSize.value
      val chunkStart = chunkIndex * chunkSize.value
      s"$chunkStart/$filename"
    }
  }
}
