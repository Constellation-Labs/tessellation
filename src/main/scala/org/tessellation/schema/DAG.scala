package org.tessellation.schema
import cats.arrow.Arrow
import cats.{Functor, Representable}
import cats.implicits._
import cats.kernel.Group
import org.tessellation.schema.Topos.Enriched

case class DAG[A, B](data: A) extends Topos[A, B]

object DAG {
  implicit val repr = new Representable[Enriched] {
    override def F: Functor[Enriched] = ???

    override type Representation = this.type

    override def index[A](f: Enriched[A]): this.type => A = ???
    // https://ncatlab.org/nlab/show/2-sheaf
    // https://ncatlab.org/nlab/show/indexed+category

    /**
     * todo use Enrichment to maintain order
     *
     * @param f
     * @tparam A
     * @return
     */
    override def tabulate[A](f: this.type => A): Enriched[A] = ???
  }
  val representation: Representable[Enriched] = Representable(repr)
}

/**
* Preserves Trace
  * @tparam A
  * @tparam B
  */
abstract class Abelian[A, B](data: A) extends Group[A]

/**
* Preserves Braiding
  * @tparam A
  * @tparam B
  */
abstract class Frobenius[A, B](data: A) extends Abelian[A, B](data)
