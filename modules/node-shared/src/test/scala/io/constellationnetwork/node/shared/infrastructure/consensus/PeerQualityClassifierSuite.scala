package io.constellationnetwork.node.shared.infrastructure.consensus

import weaver.FunSuite

object PeerQualityClassifierSuite extends FunSuite {

  test("meetsParticipationRatio uses exact integer math (no Double rounding at the boundary)") {
    val minScaled = PeerQualityClassifier.minParticipationRatioScaled(0.5)
    // exactly 0.5 meets >= 0.5
    expect(PeerQualityClassifier.meetsParticipationRatio(completed = 1, participated = 2, minScaled), "1/2 == 0.5 must meet 0.5") and
      // just below 0.5 (49/100) must fail
      expect(!PeerQualityClassifier.meetsParticipationRatio(49, 100, minScaled), "49/100 < 0.5 must not meet 0.5") and
      // just above (50/99) must pass
      expect(PeerQualityClassifier.meetsParticipationRatio(50, 99, minScaled), "50/99 > 0.5 must meet 0.5") and
      // zero participation never meets
      expect(!PeerQualityClassifier.meetsParticipationRatio(0, 0, minScaled), "0 observations must not meet ratio")
  }

  test("isChronic requires both the observation floor and a sub-threshold ratio") {
    val minObs = 30
    val minRatio = 0.5
    // below the observation floor -> never chronic regardless of ratio
    expect(!PeerQualityClassifier.isChronic(completed = 0, participated = 29, minObs, minRatio), "below floor is not chronic") and
      // at/above the floor and below the ratio -> chronic
      expect(PeerQualityClassifier.isChronic(10, 30, minObs, minRatio), "30 obs at 1/3 ratio is chronic") and
      // at the floor but meeting the ratio -> not chronic
      expect(!PeerQualityClassifier.isChronic(20, 30, minObs, minRatio), "30 obs at 2/3 ratio is not chronic") and
      // exactly at the ratio boundary -> meets, so not chronic
      expect(!PeerQualityClassifier.isChronic(15, 30, minObs, minRatio), "30 obs at exactly 0.5 is not chronic")
  }
}
