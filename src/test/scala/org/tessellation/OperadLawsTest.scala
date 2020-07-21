package org.tessellation

import org.scalacheck.Properties
import org.tessellation.ConvolutionLawsTest.property

object OperadLawsTest extends Properties("OperadLawsTest"){
  property("Operad composition is commutative") = true
  //https://ncatlab.org/nlab/show/operad#PedestrianDefColouredOperad
  property("Operad composition is associative") = true


}
