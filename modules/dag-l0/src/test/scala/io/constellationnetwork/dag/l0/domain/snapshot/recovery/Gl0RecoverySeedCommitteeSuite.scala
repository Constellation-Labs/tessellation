package io.constellationnetwork.dag.l0.domain.snapshot.recovery

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

object Gl0RecoverySeedCommitteeSuite extends SimpleIOSuite {

  private def peer(char: Char): PeerId = PeerId(Hex(char.toString * 128))

  private val peerA = peer('a')
  private val peerB = peer('b')
  private val peerC = peer('c')

  private def parse(value: String): Gl0RecoverySeedCommittee =
    Gl0RecoverySeedCommittee.parse(value).fold(throw _, identity)

  pureTest("env parser canonicalizes permutation and surrounding whitespace") {
    val first = parse(s"${peerC.value.value}, ${peerA.value.value},${peerB.value.value}")
    val second = parse(s"${peerB.value.value},${peerC.value.value},${peerA.value.value}")

    expect.same(first, second) &&
    expect.same(List(peerA, peerB, peerC), first.committee.toList)
  }

  pureTest("env parser rejects empty and structurally empty entries") {
    val values = List(
      "",
      "   ",
      s",${peerA.value.value}",
      s"${peerA.value.value},",
      s"${peerA.value.value},,${peerB.value.value}"
    )

    expect(values.forall(Gl0RecoverySeedCommittee.parse(_).isLeft))
  }

  pureTest("env parser rejects duplicates before SortedSet could hide them") {
    val duplicate = s"${peerA.value.value},${peerB.value.value},${peerA.value.value}"

    expect(Gl0RecoverySeedCommittee.parse(duplicate).left.exists(_.reason.contains("duplicate")))
  }

  pureTest("env parser rejects uppercase, non-hex, and wrong-length PeerIds") {
    val malformed = List(peerA.value.value.toUpperCase, "z" * 128, "a" * 127, "a" * 129)

    expect(malformed.forall(value => Gl0RecoverySeedCommittee.parse(value).isLeft))
  }

  pureTest("static validation accepts a viable canonical recovery committee") {
    val seed = parse(s"${peerC.value.value},${peerA.value.value},${peerB.value.value}")

    val result = Gl0RecoverySeedCommittee.validate(
      seed,
      requiredMember = peerA,
      seedlist = Set(peerA, peerB, peerC),
      allowanceList = Some(Set(peerA, peerB, peerC)),
      maxFacilitatorCount = Some(3),
      quorumThresholdFraction = 2.0 / 3.0
    )

    expect.same(Right(seed), result)
  }

  pureTest("static validation rejects missing self, membership-boundary violations, and selector truncation") {
    val seed = parse(s"${peerA.value.value},${peerB.value.value},${peerC.value.value}")
    val common = (
      requiredMember: PeerId,
      seedlist: Set[PeerId],
      allowance: Option[Set[PeerId]],
      max: Option[Int]
    ) =>
      Gl0RecoverySeedCommittee.validate(
        seed,
        requiredMember,
        seedlist,
        allowance,
        max,
        quorumThresholdFraction = 2.0 / 3.0
      )

    expect(common(peer('d'), Set(peerA, peerB, peerC), None, None).isLeft) &&
    expect(common(peerA, Set(peerA, peerB), None, None).isLeft) &&
    expect(common(peerA, Set(peerA, peerB, peerC), Some(Set(peerA, peerB)), None).isLeft) &&
    expect(common(peerA, Set(peerA, peerB, peerC), None, Some(2)).isLeft)
  }

  pureTest("static validation rejects singleton and next-seat-infeasible committees") {
    val singleton = parse(peerA.value.value)
    val pair = parse(s"${peerA.value.value},${peerB.value.value}")

    val singletonResult = Gl0RecoverySeedCommittee.validate(
      singleton,
      peerA,
      Set(peerA),
      None,
      None,
      quorumThresholdFraction = 2.0 / 3.0
    )
    val unanimousResult = Gl0RecoverySeedCommittee.validate(
      pair,
      peerA,
      Set(peerA, peerB),
      None,
      None,
      quorumThresholdFraction = 1.0
    )

    expect(singletonResult.isLeft) && expect(unanimousResult.isLeft)
  }
}
