package org.tessellation.hypergraph

import enumeratum.EnumEntry

/** Our basic set of allowed edge hash types */
sealed trait EdgeHashType extends EnumEntry

/**
  * Wrapper for encapsulating a typed hash reference
  *
  * @param hashReference : String of hashed value or reference to be signed
  * @param hashType : Strictly typed from set of allowed edge formats
  */ // baseHash Temporary to debug heights missing
case class TypedEdgeHash(
  hashReference: String,
  hashType: EdgeHashType,
  baseHash: Option[String] = None
)

/**
  * Basic edge format for linking two hashes with an optional piece of data attached. Similar to GraphX format.
  * Left is topologically ordered before right
  *
  * @param parents : HyperEdge parent references
  * @param data    : Optional hash reference to attached information
  */
case class HyperEdge(
  parents: Seq[TypedEdgeHash],
  data: TypedEdgeHash
)
