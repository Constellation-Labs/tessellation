package io.constellationnetwork.security.mpt

import cats.data.NonEmptySet
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.generators._
import io.constellationnetwork.schema.mpt.GlobalStateConverter.StateChangesAccumulator
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt.producer.{InMemoryMerklePatriciaProducer, MerklePatriciaError}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import weaver.MutableIOSuite

/** Debug test suite for state mutation sequences.
  *
  * Tests various sequences of state changes to try to reproduce non-determinism between incremental sync and full rebuild.
  */
object MptStateMutationDebugSuite extends MutableIOSuite {

  type Res = (JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, Res] = for {
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    h = Hasher.forJson[IO]
  } yield (j, h, sp)

  private val addr1 = addressGen.sample.get
  private val addr2 = addressGen.sample.get
  private val addr3 = addressGen.sample.get

  implicit val stateProofSelector: GlobalStateProofSelector = GlobalStateProofSelector(SnapshotOrdinal(NonNegLong(Long.MaxValue)))

  private def createSignedStake(
    source: Address,
    nodeId: PeerId,
    amount: Long,
    tokenLockRef: Hash = Hash.empty
  ): Signed[UpdateDelegatedStake.Create] =
    Signed(
      UpdateDelegatedStake.Create(
        source = source,
        nodeId = nodeId,
        amount = DelegatedStakeAmount(NonNegLong.unsafeFrom(amount)),
        fee = DelegatedStakeFee(0L),
        tokenLockRef = tokenLockRef
      ),
      NonEmptySet.one[SignatureProof](SignatureProof(nodeId.toId, Signature(Hex(Hash.empty.value))))
    )

  private val nodeId1 = Id(Hex("1111111111111111" * 8)).toPeerId
  private val nodeId2 = Id(Hex("2222222222222222" * 8)).toPeerId

  case class StateSnapshot(
    ordinal: SnapshotOrdinal,
    delegatedStakes: SortedMap[Address, SortedSet[DelegatedStakeRecord]],
    balances: SortedMap[Address, Balance] = SortedMap.empty
  )

  // Helper to build MPT and get root hash
  private def buildMptRoot(
    state: StateSnapshot
  )(implicit j: JsonSerializer[IO], h: Hasher[IO], sp: SecurityProvider[IO]): IO[Either[MerklePatriciaError, MptRoot]] = {
    val info = GlobalSnapshotInfo.empty.copy(
      activeDelegatedStakes = if (state.delegatedStakes.nonEmpty) Some(state.delegatedStakes) else None,
      balances = state.balances
    )
    for {
      producer <- InMemoryMerklePatriciaProducer.make[IO]()
      store <- MptStore.make[IO, GlobalStateKey](producer, GlobalStateKey.toHex[IO])
      _ <- store.syncFromGlobalSnapshotInfo(info, state.ordinal)
      trie <- store.build(state.ordinal)
    } yield trie.map(_.rootHash)
  }

  // Helper to build MPT incrementally and get root hash
  private def buildMptRootIncremental(
    states: List[StateSnapshot]
  )(implicit j: JsonSerializer[IO], h: Hasher[IO], sp: SecurityProvider[IO]): IO[Either[MerklePatriciaError, MptRoot]] =
    for {
      producer <- InMemoryMerklePatriciaProducer.make[IO]()
      store <- MptStore.make[IO, GlobalStateKey](producer, GlobalStateKey.toHex[IO])
      _ <- states.traverse_ { state =>
        val acc = StateChangesAccumulator(
          activeDelegatedStakes = state.delegatedStakes,
          balances = state.balances
        )
        store.syncFromStateChanges(acc, state.ordinal)
      }
      finalOrdinal = states.lastOption.map(_.ordinal).getOrElse(SnapshotOrdinal(NonNegLong(1L)))
      trie <- store.build(finalOrdinal)
    } yield trie.map(_.rootHash)

  test("mutation: create single delegated stake") { res =>
    implicit val (j, h, sp) = res

    val stake = createSignedStake(addr1, nodeId1, 1000L)
    val ordinal = SnapshotOrdinal(NonNegLong(1L))
    val record = DelegatedStakeRecord(stake, ordinal, Balance(0L), None, None)

    val state = StateSnapshot(
      ordinal = ordinal,
      delegatedStakes = SortedMap(addr1 -> SortedSet(record))
    )

    for {
      fullRoot <- buildMptRoot(state)
      incRoot <- buildMptRootIncremental(List(state))
      _ <- IO.println(s"[CREATE] Full: $fullRoot, Inc: $incRoot, Match: ${fullRoot == incRoot}")
    } yield expect(fullRoot == incRoot)
  }

  test("mutation: create then update rewards") { res =>
    implicit val (j, h, sp) = res

    val stake = createSignedStake(addr1, nodeId1, 1000L)
    val ordinal1 = SnapshotOrdinal(NonNegLong(1L))
    val ordinal2 = SnapshotOrdinal(NonNegLong(2L))

    // State at ordinal 1: initial stake
    val record1 = DelegatedStakeRecord(stake, ordinal1, Balance(0L), None, None)
    val state1 = StateSnapshot(ordinal1, SortedMap(addr1 -> SortedSet(record1)))

    // State at ordinal 2: rewards updated
    val record2 = DelegatedStakeRecord(stake, ordinal1, Balance(100L), None, None)
    val state2 = StateSnapshot(ordinal2, SortedMap(addr1 -> SortedSet(record2)))

    for {
      // Full rebuild at ordinal 2
      fullRoot <- buildMptRoot(state2)
      // Incremental: ordinal1 -> ordinal2
      incRoot <- buildMptRootIncremental(List(state1, state2))
      _ <- IO.println(s"[UPDATE REWARDS] Full: $fullRoot, Inc: $incRoot, Match: ${fullRoot == incRoot}")
    } yield expect(fullRoot == incRoot)
  }

  test("mutation: create stake, then add second stake for same address") { res =>
    implicit val (j, h, sp) = res

    val stake1 = createSignedStake(addr1, nodeId1, 1000L)
    val stake2 = createSignedStake(addr1, nodeId2, 2000L, Hash("different"))
    val ordinal1 = SnapshotOrdinal(NonNegLong(1L))
    val ordinal2 = SnapshotOrdinal(NonNegLong(2L))

    // State at ordinal 1: one stake
    val record1 = DelegatedStakeRecord(stake1, ordinal1, Balance(0L), None, None)
    val state1 = StateSnapshot(ordinal1, SortedMap(addr1 -> SortedSet(record1)))

    // State at ordinal 2: two stakes for same address
    val record2 = DelegatedStakeRecord(stake2, ordinal2, Balance(0L), None, None)
    val state2 = StateSnapshot(ordinal2, SortedMap(addr1 -> SortedSet(record1, record2)))

    for {
      fullRoot <- buildMptRoot(state2)
      incRoot <- buildMptRootIncremental(List(state1, state2))
      _ <- IO.println(s"[ADD SECOND STAKE] Full: $fullRoot, Inc: $incRoot, Match: ${fullRoot == incRoot}")
    } yield expect(fullRoot == incRoot)
  }

  test("mutation: create stakes for multiple addresses across ordinals") { res =>
    implicit val (j, h, sp) = res

    val stake1 = createSignedStake(addr1, nodeId1, 1000L)
    val stake2 = createSignedStake(addr2, nodeId1, 2000L)
    val stake3 = createSignedStake(addr3, nodeId2, 3000L)

    val ordinal1 = SnapshotOrdinal(NonNegLong(1L))
    val ordinal2 = SnapshotOrdinal(NonNegLong(2L))
    val ordinal3 = SnapshotOrdinal(NonNegLong(3L))

    val record1 = DelegatedStakeRecord(stake1, ordinal1, Balance(0L), None, None)
    val record2 = DelegatedStakeRecord(stake2, ordinal2, Balance(0L), None, None)
    val record3 = DelegatedStakeRecord(stake3, ordinal3, Balance(0L), None, None)

    // Incremental states
    val state1 = StateSnapshot(ordinal1, SortedMap(addr1 -> SortedSet(record1)))
    val state2 = StateSnapshot(
      ordinal2,
      SortedMap(
        addr1 -> SortedSet(record1),
        addr2 -> SortedSet(record2)
      )
    )
    val state3 = StateSnapshot(
      ordinal3,
      SortedMap(
        addr1 -> SortedSet(record1),
        addr2 -> SortedSet(record2),
        addr3 -> SortedSet(record3)
      )
    )

    for {
      fullRoot <- buildMptRoot(state3)
      incRoot <- buildMptRootIncremental(List(state1, state2, state3))
      _ <- IO.println(s"[MULTI ADDRESS] Full: $fullRoot, Inc: $incRoot, Match: ${fullRoot == incRoot}")
    } yield expect(fullRoot == incRoot)
  }

  test("mutation: create stake, update rewards, add another stake") { res =>
    implicit val (j, h, sp) = res

    val stake1 = createSignedStake(addr1, nodeId1, 1000L)
    val stake2 = createSignedStake(addr1, nodeId2, 2000L, Hash("second"))

    val ordinal1 = SnapshotOrdinal(NonNegLong(1L))
    val ordinal2 = SnapshotOrdinal(NonNegLong(2L))
    val ordinal3 = SnapshotOrdinal(NonNegLong(3L))

    // Ordinal 1: create first stake
    val record1_v1 = DelegatedStakeRecord(stake1, ordinal1, Balance(0L), None, None)
    val state1 = StateSnapshot(ordinal1, SortedMap(addr1 -> SortedSet(record1_v1)))

    // Ordinal 2: rewards updated
    val record1_v2 = DelegatedStakeRecord(stake1, ordinal1, Balance(50L), None, None)
    val state2 = StateSnapshot(ordinal2, SortedMap(addr1 -> SortedSet(record1_v2)))

    // Ordinal 3: add second stake, keep updated rewards
    val record2 = DelegatedStakeRecord(stake2, ordinal3, Balance(0L), None, None)
    val state3 = StateSnapshot(ordinal3, SortedMap(addr1 -> SortedSet(record1_v2, record2)))

    for {
      fullRoot <- buildMptRoot(state3)
      incRoot <- buildMptRootIncremental(List(state1, state2, state3))
      _ <- IO.println(s"[CREATE+UPDATE+ADD] Full: $fullRoot, Inc: $incRoot, Match: ${fullRoot == incRoot}")
    } yield expect(fullRoot == incRoot)
  }

  test("mutation: mixed state with balances and delegated stakes") { res =>
    implicit val (j, h, sp) = res

    val stake = createSignedStake(addr1, nodeId1, 1000L)
    val ordinal1 = SnapshotOrdinal(NonNegLong(1L))
    val ordinal2 = SnapshotOrdinal(NonNegLong(2L))

    // Ordinal 1: balance only
    val state1 = StateSnapshot(
      ordinal1,
      delegatedStakes = SortedMap.empty,
      balances = SortedMap(addr1 -> Balance(5000L))
    )

    // Ordinal 2: balance + delegated stake
    val record = DelegatedStakeRecord(stake, ordinal2, Balance(0L), None, None)
    val state2 = StateSnapshot(
      ordinal2,
      delegatedStakes = SortedMap(addr1 -> SortedSet(record)),
      balances = SortedMap(addr1 -> Balance(4000L)) // balance decreased due to stake
    )

    for {
      fullRoot <- buildMptRoot(state2)
      incRoot <- buildMptRootIncremental(List(state1, state2))
      _ <- IO.println(s"[BALANCE+STAKE] Full: $fullRoot, Inc: $incRoot, Match: ${fullRoot == incRoot}")
    } yield expect(fullRoot == incRoot)
  }

  test("mutation: rapid succession of changes") { res =>
    implicit val (j, h, sp) = res

    val stakes = (1 to 5).map { i =>
      createSignedStake(addr1, if (i % 2 == 0) nodeId1 else nodeId2, i * 1000L, Hash(s"stake$i"))
    }

    val ordinals = List(
      SnapshotOrdinal(NonNegLong(1L)),
      SnapshotOrdinal(NonNegLong(2L)),
      SnapshotOrdinal(NonNegLong(3L)),
      SnapshotOrdinal(NonNegLong(4L)),
      SnapshotOrdinal(NonNegLong(5L))
    )
    val rewards = List(Balance(0L), Balance(10L), Balance(20L), Balance(30L), Balance(40L))

    val states = stakes.zipWithIndex.map {
      case (_, idx) =>
        val ordinal = ordinals(idx)
        val records = stakes.take(idx + 1).zipWithIndex.map {
          case (s, j) =>
            DelegatedStakeRecord(s, ordinals(j), rewards(j), None, None)
        }
        StateSnapshot(ordinal, SortedMap(addr1 -> SortedSet.from(records)))
    }.toList

    val finalState = states.last

    for {
      fullRoot <- buildMptRoot(finalState)
      incRoot <- buildMptRootIncremental(states)
      _ <- IO.println(s"[RAPID CHANGES] Full: $fullRoot, Inc: $incRoot, Match: ${fullRoot == incRoot}")
      _ <- IO.println(s"[RAPID CHANGES] Final state has ${finalState.delegatedStakes.values.flatten.size} records")
    } yield expect(fullRoot == incRoot)
  }

  test("mutation: simulate accumulator only having deltas (not full state)") { res =>
    implicit val (j, h, sp) = res

    // This simulates what might happen if the accumulator only has CHANGES
    // rather than the full state

    val stake1 = createSignedStake(addr1, nodeId1, 1000L)
    val stake2 = createSignedStake(addr2, nodeId1, 2000L)

    val ordinal1 = SnapshotOrdinal(NonNegLong(1L))
    val ordinal2 = SnapshotOrdinal(NonNegLong(2L))

    val record1 = DelegatedStakeRecord(stake1, ordinal1, Balance(0L), None, None)
    val record2 = DelegatedStakeRecord(stake2, ordinal2, Balance(0L), None, None)

    // Full final state
    val fullState = StateSnapshot(
      ordinal2,
      SortedMap(
        addr1 -> SortedSet(record1),
        addr2 -> SortedSet(record2)
      )
    )

    for {
      // Full rebuild
      fullProducer <- InMemoryMerklePatriciaProducer.make[IO]()
      fullStore <- MptStore.make[IO, GlobalStateKey](fullProducer, GlobalStateKey.toHex[IO])
      fullInfo = GlobalSnapshotInfo.empty.copy(activeDelegatedStakes = Some(fullState.delegatedStakes))
      _ <- fullStore.syncFromGlobalSnapshotInfo(fullInfo, ordinal2)
      fullTrie <- fullStore.build(ordinal2)
      fullRoot = fullTrie.map(_.rootHash)

      // Incremental with DELTA ONLY (missing addr1 at ordinal2)
      incProducer <- InMemoryMerklePatriciaProducer.make[IO]()
      incStore <- MptStore.make[IO, GlobalStateKey](incProducer, GlobalStateKey.toHex[IO])

      // Ordinal 1: addr1 only
      acc1 = StateChangesAccumulator(activeDelegatedStakes = SortedMap(addr1 -> SortedSet(record1)))
      _ <- incStore.syncFromStateChanges(acc1, ordinal1)

      // Ordinal 2: ONLY the new addr2 (simulating delta-only accumulator)
      acc2Delta = StateChangesAccumulator(activeDelegatedStakes = SortedMap(addr2 -> SortedSet(record2)))
      _ <- incStore.syncFromStateChanges(acc2Delta, ordinal2)

      incTrie <- incStore.build(ordinal2)
      incRoot = incTrie.map(_.rootHash)

      _ <- IO.println(s"[DELTA ONLY] Full: $fullRoot, Inc (delta): $incRoot, Match: ${fullRoot == incRoot}")
      // This SHOULD match because sync adds/updates, doesn't replace
    } yield expect(fullRoot == incRoot)
  }

  test("mutation: verify removed key handling") { res =>
    implicit val (j, h, sp) = res

    val stake1 = createSignedStake(addr1, nodeId1, 1000L)
    val stake2 = createSignedStake(addr2, nodeId1, 2000L)

    val ordinal1 = SnapshotOrdinal(NonNegLong(1L))
    val ordinal2 = SnapshotOrdinal(NonNegLong(2L))

    val record1 = DelegatedStakeRecord(stake1, ordinal1, Balance(0L), None, None)
    val record2 = DelegatedStakeRecord(stake2, ordinal1, Balance(0L), None, None)

    // Ordinal 1: both addresses have stakes
    val state1 = StateSnapshot(
      ordinal1,
      SortedMap(
        addr1 -> SortedSet(record1),
        addr2 -> SortedSet(record2)
      )
    )

    // Ordinal 2: addr2's stake is removed (withdrawn)
    val state2 = StateSnapshot(
      ordinal2,
      SortedMap(
        addr1 -> SortedSet(record1)
      )
    )

    for {
      fullRoot <- buildMptRoot(state2)

      // Incremental with removal
      incProducer <- InMemoryMerklePatriciaProducer.make[IO]()
      incStore <- MptStore.make[IO, GlobalStateKey](incProducer, GlobalStateKey.toHex[IO])

      acc1 = StateChangesAccumulator(activeDelegatedStakes = state1.delegatedStakes)
      _ <- incStore.syncFromStateChanges(acc1, ordinal1)

      // At ordinal 2, we need to remove addr2's stake
      acc2 = StateChangesAccumulator(
        activeDelegatedStakes = state2.delegatedStakes,
        removedDelegatedStakeKeys = Set(addr2)
      )
      _ <- incStore.syncFromStateChanges(acc2, ordinal2)

      incTrie <- incStore.build(ordinal2)
      incRoot = incTrie.map(_.rootHash)

      _ <- IO.println(s"[REMOVAL] Full: $fullRoot, Inc: $incRoot, Match: ${fullRoot == incRoot}")
    } yield expect(fullRoot == incRoot)
  }
}
