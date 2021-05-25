package org.tessellation

import org.tessellation.ConvolutionLawsTest.property

class CatLawsTest {

  /**
    * Monadic data is 2-topos, think fibration https://ncatlab.org/nlab/show/Grothendieck+fibration
    * "define a functor enriched over KK and a natural transformation enriched over KK, obtaining a strict 2-category of
    * KK-enriched categories, K K -Cat" https://ncatlab.org/nlab/show/enriched+category
    */
  property("Hom is o-cell") = true
  property("Cell is 1-cell") = true
  property("Cocell is 2-cell <-> cell morphism") = true
  //todo define Cocell tensor in terms of inject

  /**
    * Convolution is a monoidal topos https://ncatlab.org/nlab/show/monoidal+topos
    */
  property("Cell Co/Algebras are monoidal") = true
  property("Edge construction follows cartesian product") = true
}
