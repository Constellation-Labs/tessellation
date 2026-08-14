package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.effect.{IO, Ref}
import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.consensus.PeerDeclarations
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.{Facility, Proposal}
import io.constellationnetwork.node.shared.infrastructure.consensus.state.Candidates
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import eu.timepit.refined.types.numeric.NonNegLong
import weaver.SimpleIOSuite

object GlobalSnapshotSoftResetGateSuite extends SimpleIOSuite {

  private val selfId = PeerId(Hex("01" * 64))
  private val peerId = PeerId(Hex("02" * 64))
  private val facilitatorsHash = Hash.fromBytes("facilitators".getBytes("UTF-8"))
  private val parentHash = Hash.fromBytes("parent".getBytes("UTF-8"))

  private val facility = Facility(
    eventHashes = Set.empty,
    candidates = Candidates(Set.empty),
    trigger = EventTrigger.some,
    facilitatorsHash = facilitatorsHash,
    lastGlobalSnapshotOrdinal = SnapshotOrdinal(NonNegLong(1L)),
    lastSnapshotHash = parentHash
  )

  private val proposal = Proposal(
    hash = Hash.fromBytes("proposal".getBytes("UTF-8")),
    facilitatorsHash = facilitatorsHash,
    lastSnapshotHash = parentHash,
    view = 0L,
    vcc = None
  )

  private def allowed(
    id: PeerId = peerId,
    ready: Boolean = true,
    atOrAhead: Boolean = true,
    declaration: PeerDeclarations = PeerDeclarations.empty.copy(facility = facility.some)
  ): Boolean =
    GlobalSnapshotConsensusStateAdvancer.isUsefulSoftResetBootstrapDeclaration(
      selfId,
      id,
      ready,
      atOrAhead,
      declaration
    )

  test("same-key soft reset requires an external Ready peer at-or-ahead with a retained Facility") {
    val proposalOnly = PeerDeclarations.empty.copy(proposal = proposal.some)

    IO.pure(
      expect(allowed())
        .and(expect(!allowed(id = selfId)))
        .and(expect(!allowed(ready = false)))
        .and(expect(!allowed(atOrAhead = false)))
        .and(expect(!allowed(declaration = PeerDeclarations.empty)))
        .and(expect(!allowed(declaration = proposalOnly)))
    )
  }

  test("destructive soft reset schedules restart before best-effort bookkeeping and observability") {
    for {
      order <- Ref.of[IO, List[String]](List.empty)
      result <- GlobalSnapshotConsensusStateAdvancer
        .completeDestructiveSoftReset[IO](
          order.update(_ :+ "restart"),
          order.update(_ :+ "tick").as(4),
          order.update(_ :+ "clear") >> IO.raiseError(new RuntimeException("clear failed")),
          count => order.update(_ :+ s"observe-$count") >> IO.raiseError(new RuntimeException("metrics failed")),
          fallbackCount = 3
        )
        .attempt
      observed <- order.get
    } yield expect(result.isRight) && expect.same(List("restart", "tick", "clear", "observe-4"), observed)
  }

  test("destructive soft reset still restarts when reset-count bookkeeping fails") {
    for {
      order <- Ref.of[IO, List[String]](List.empty)
      _ <- GlobalSnapshotConsensusStateAdvancer.completeDestructiveSoftReset[IO](
        order.update(_ :+ "restart"),
        IO.raiseError(new RuntimeException("counter failed")),
        order.update(_ :+ "clear"),
        count => order.update(_ :+ s"observe-$count"),
        fallbackCount = 7
      )
      observed <- order.get
    } yield expect.same(List("restart", "clear", "observe-7"), observed)
  }
}
