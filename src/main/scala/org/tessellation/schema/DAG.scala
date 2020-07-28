package org.tessellation.schema
import higherkindness.droste.{Algebra, Coalgebra}
import org.tessellation.schema.Topos.Enriched

class DAG extends Topos[Abelian]

class Abelian[A, B] extends Group[Ω, Ω] {
//  override  val coalgebra: Coalgebra[Enriched, Ω] = ???
//
//  override  val algebra: Algebra[Enriched, Ω] = ???
}
