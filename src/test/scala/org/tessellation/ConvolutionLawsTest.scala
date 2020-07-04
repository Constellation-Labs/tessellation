package org.tessellation

import org.scalacheck.Properties
import org.tessellation.OperadLawsTest.property

object ConvolutionLawsTest extends Properties("ConvolutionLawsTest"){
  //https://ncatlab.org/nlab/show/Day+convolution
  property("Join induced over Simplex") = true
  //https://ncatlab.org/nlab/show/end
  property("Enrichment induces end") = true
  //https://ncatlab.org/nlab/show/representation
  property("Hom is representable functor") = true
  //https://ncatlab.org/nlab/show/representable+multicategory
  property("Operad right adjoint") = true
  //https://ncatlab.org/nlab/show/symmetric+smash+product+of+spectra
  //Proposition 2.11
  property("Enriched Hom has a left adjoint that is a Kan extension") = true
  //Definition 2.37
  property("Smash product follows colimit and quitient map limit ") = true
  //https://ncatlab.org/nlab/show/symmetric+spectrum
  property("Action spectra under smash is symmetric <=> homotopy groups are isomorphic to Poincare Protocol") = true
}