package org.tessellation.infrastructure.trust

import scala.collection.immutable.Map

/** See example usage here :
  * https://github.com/djelenc/alpha-testbed/blob/83e669e69463872aa84017051392c885d4183d1d/src/test/java/atb/trustmodel/EigenTrustTMTest.java#L24
  * Reimplemented from atb.trustmodel.EigenTrust https://nlp.stanford.edu/pubs/eigentrust.pdf
  *
  * This uses only a subset of code -- ignores the opinion / experience code implemented by raw example and expects a raw C_ij trust matrix
  * to be supplied. Also mostly ignores pre-trust vector
  *
  * Code can be optimized with use of breeze matrix
  */
object EigenTrust {

  def calculate(trustMatrix: Array[Array[Double]]): Map[Int, Double] = {
    val trustMatrixNormalized = trustMatrix.map { row =>
      val sum = row.sum
      row.map(_ / sum)
    }

    computeFromTrustMatrix(trustMatrixNormalized, Array.empty)
  }

  def calculate(trustNodes: List[TrustNode]): Map[Int, Double] = calculate(convert(trustNodes))

  def convert(trustNodes: List[TrustNode]): Array[Array[Double]] = {
    val size = trustNodes.size
    trustNodes
      .sortBy(_.id)
      .map { tn =>
        val array = Array.fill(size)(0d)
        tn.edges.foreach { e =>
          // Disregard negative edges as EigenTrust doesn't support it.
          if (e.trust > 0) {
            array(e.dst) = e.trust
          }
        }
        // Ensure self trust is one
        array(tn.id) = 1
        array
      }
      .toArray
  }

  def hasConverged(
    t_new: Array[Double],
    t_old: Array[Double],
    epsilon: Double = 0.01
  ): Boolean = {
    var sum = 0d
    for (i <- t_old.indices)
      sum += (t_new(i) - t_old(i)) * (t_new(i) - t_old(i))
    Math.sqrt(sum) < epsilon
  }

  def computeFromTrustMatrix(
    trustMatrixCij: Array[Array[Double]],
    preTrustVectorInput: Array[Double],
    preTrustWeight: Double = 0d,
    epsilon: Double = 0.01d
  ): Map[Int, Double] = { // execute algorithm

    var preTrustVector = preTrustVectorInput
    if (preTrustVector.isEmpty) {
      val length = trustMatrixCij.length
      preTrustVector = Array.fill(length)(0d)
      trustMatrixCij.foreach {
        _.zipWithIndex.foreach {
          case (col, j) =>
            preTrustVector(j) += col
        }
      }
      preTrustVector = preTrustVector.map(_ / length)
    }
    val length = preTrustVector.length
    val t_new = new Array[Double](length)
    val t_old = new Array[Double](length)
    // t_new = p
    System.arraycopy(preTrustVector, 0, t_new, 0, preTrustVector.length)
    do { // t_old = t_new
      System.arraycopy(t_new, 0, t_old, 0, t_new.length)
      // t_new = C * t_old
      for (row <- t_old.indices) {
        var sum = 0d
        for (col <- t_old.indices)
          sum += trustMatrixCij(row)(col) * t_old(col)
        t_new(row) = sum
      }
      // t_new = (1 - weight) * t_new + weight * p
      for (i <- t_old.indices) {
        val weighted = preTrustWeight * preTrustVector(i)
        t_new(i) = (1 - preTrustWeight) * t_new(i) + weighted
      }
    } while (
      !hasConverged(t_new, t_old, epsilon)
    )
    val trust = new scala.collection.mutable.HashMap[Int, Double]
    for (i <- t_old.indices)
      trust.put(i, t_new(i))
    trust.toMap
  }

}
