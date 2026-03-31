package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.IO

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

object EvictionVoteTrackerSuite extends SimpleIOSuite {

  private def pid(name: String): PeerId = PeerId(Hex(name.getBytes("UTF-8").map(b => f"$b%02x").mkString))

  private val alice = pid("alice")
  private val bob = pid("bob")
  private val carol = pid("carol")
  private val dave = pid("dave")

  test("records a vote and retrieves it") {
    EvictionVoteTracker.make[IO].flatMap { tracker =>
      tracker.voteToEvict(alice, bob) >>
        tracker.getEvictionVotes.map { votes =>
          expect(votes(bob) == Set(alice))
        }
    }
  }

  test("accumulates votes from multiple voters for the same target") {
    EvictionVoteTracker.make[IO].flatMap { tracker =>
      tracker.voteToEvict(alice, carol) >>
        tracker.voteToEvict(bob, carol) >>
        tracker.getEvictionVotes.map { votes =>
          expect(votes(carol) == Set(alice, bob))
        }
    }
  }

  test("tracks votes for multiple targets independently") {
    EvictionVoteTracker.make[IO].flatMap { tracker =>
      tracker.voteToEvict(alice, bob) >>
        tracker.voteToEvict(alice, carol) >>
        tracker.getEvictionVotes.map { votes =>
          expect(votes(bob) == Set(alice)) &&
          expect(votes(carol) == Set(alice))
        }
    }
  }

  test("duplicate votes from same voter are idempotent") {
    EvictionVoteTracker.make[IO].flatMap { tracker =>
      tracker.voteToEvict(alice, bob) >>
        tracker.voteToEvict(alice, bob) >>
        tracker.getEvictionVotes.map { votes =>
          expect(votes(bob) == Set(alice)) &&
          expect(votes(bob).size == 1)
        }
    }
  }

  test("hasSupermajorityVotes returns true when threshold met") {
    EvictionVoteTracker.make[IO].flatMap { tracker =>
      // 3/4 voters = 75% >= 67% threshold, ceil(4 * 0.67) = 3
      tracker.voteToEvict(alice, dave) >>
        tracker.voteToEvict(bob, dave) >>
        tracker.voteToEvict(carol, dave) >>
        tracker.hasSupermajorityVotes(dave, totalFacilitators = 4, threshold = 0.67).map { result =>
          expect(result)
        }
    }
  }

  test("hasSupermajorityVotes returns false when below threshold") {
    EvictionVoteTracker.make[IO].flatMap { tracker =>
      // 1/3 voters = 33% < 67% threshold
      tracker.voteToEvict(alice, dave) >>
        tracker.hasSupermajorityVotes(dave, totalFacilitators = 3, threshold = 0.67).map { result =>
          expect(!result)
        }
    }
  }

  test("hasSupermajorityVotes returns false for unvoted target") {
    EvictionVoteTracker.make[IO].flatMap { tracker =>
      tracker.hasSupermajorityVotes(dave, totalFacilitators = 4, threshold = 0.5).map { result =>
        expect(!result)
      }
    }
  }

  test("clearVotes removes all votes") {
    EvictionVoteTracker.make[IO].flatMap { tracker =>
      tracker.voteToEvict(alice, bob) >>
        tracker.voteToEvict(carol, dave) >>
        tracker.clearVotes >>
        tracker.getEvictionVotes.map { votes =>
          expect(votes.isEmpty)
        }
    }
  }

  test("votes after clear start fresh") {
    EvictionVoteTracker.make[IO].flatMap { tracker =>
      tracker.voteToEvict(alice, bob) >>
        tracker.voteToEvict(carol, bob) >>
        tracker.clearVotes >>
        tracker.voteToEvict(alice, bob) >>
        tracker.getEvictionVotes.map { votes =>
          expect(votes(bob) == Set(alice)) &&
          expect(votes(bob).size == 1)
        }
    }
  }
}
