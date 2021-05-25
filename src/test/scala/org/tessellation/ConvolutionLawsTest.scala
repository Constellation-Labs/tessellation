package org.tessellation

import org.scalacheck.Properties
import org.tessellation.OperadLawsTest.property
import cats.laws.StrongLaws

object ConvolutionLawsTest extends Properties("ConvolutionLawsTest") {

  /**
    * Enrichment induces ends (colimit) and coends (limit) of homotopy groups isomorphic to Poincare Protocol
    * https://ncatlab.org/nlab/show/end "Replace "vector" with "functor" and you get a colimit."
    */
  //https://ncatlab.org/nlab/show/representable+multicategory
  //todo show that all actions come from n-ary tensor injections
  property("Hom is right adjoint") = true

  //https://ncatlab.org/nlab/show/representation
  //todo show obeys RepresentableLaws https://typelevel.org/cats/api/cats/Representable.html
  property("Hom is a representable functor") = true
  property("There exists covariant action λc,d,e:F(c,d)⊗C(d,e)→F(c,e)") = true
  property("There exists contravariant action ρc,d,e:F(d,e)⊗C(c,d)→F(c,e).") = true

  /**
    * Enrichment is symmetric monoidal enriched
    * "In functional programming,these monoids give rise to the notion of Applicative."
    */
  property("tensor is commutative") = true
  /*
  B y,x∘B x,y=1 x⊗
   */
  property("Composition of opposite arrows is identity") = true

  /**
    * Simplex induces Join operator
    */
  //https://bartoszmilewski.com/2018/12/11/keep-it-simplex-stupid/
  //todo just need flatten/flatmap
  //import cats._, cats.data._, cats.implicits._
  //def join[F[_]: FlatMap, A](fa: F[F[A]]): F[A] =
  //         fa.flatten
  property("The join of two simplices is another simplex: Δ[k]⋆Δ[l]=Δ[k+l+1]") = true
  //todo should just need to show how mixing works, contravariance should show over flatmap, show dimensions increase
  property("The cone over the n-simplex is the (n+1)-simplex: Δ[0]⋆Δ[n]=Δ[n+1]") = true
  //todo just show that flatmap over the the enrichment follows correct ordering
  property("The join of two simplices is a reduceByKey over an indexed copower") = true

  /**
    * Monoid laws form Day convolution monoidal category <=> action spectra under tensor is smash and is symmetric
    */
  //https://ncatlab.org/nlab/show/Day+convolution
  //https://ncatlab.org/nlab/show/symmetric+spectrum
  //todo show that tensor of two chains is another chain such that this is for any two symmetric Cell spectra A and B
  // given degreewise by the wedge sum of component spaces summing to that total degree
  property("Tensor is a valid smash product for Cell spectra") = true
  //https://ncatlab.org/nlab/show/symmetric+smash+product+of+spectra
  //Proposition 2.11
  property("Enriched Hom has a left adjoint that is a Kan extension") = true
  //todo proof, left adjoint is given by the coend
  //https://gist.github.com/chenharryhua/e2fc1b5816b471d943d79187a88267ff
  //Definition 2.37
  property("Smash product follows colimit and quotient map limit ") = true
  //todo ^ is this not equivalent to Enrichment is symmetric monoidal enriched
}
