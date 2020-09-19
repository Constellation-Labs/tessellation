package org.tessellation

import org.tessellation.hypergraph.EdgePartitioner.EdgePartitioner

import scala.collection.mutable

class Cluster[F[_]] {
  val ipNodeData = mutable.HashMap[String, EdgePartitioner[F]]()
}
