package io.constellationnetwork.schema

import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.generators.addressGen
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.schema.mpt.GlobalStateFieldId
import io.constellationnetwork.schema.transaction.TransactionReference
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import org.scalacheck.Gen
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object GlobalSnapshotSchemaMigrationSuite extends MutableIOSuite with Checkers {

  type Res = (HasherSelector[IO], JsonSerializer[IO])

  override def sharedResource: Resource[IO, Res] =
    for {
      implicit0(kryo: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
      implicit0(json: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].toResource
    } yield
      (
        HasherSelector.forSync[IO](
          Hasher.forJson[IO],
          Hasher.forKryo[IO],
          hashSelect = new HashSelect { def select(ordinal: SnapshotOrdinal): HashLogic = JsonHash }
        ),
        json
      )

  test("GlobalSnapshotInfoV1 converts to GlobalSnapshotInfo preserving core fields") { implicit res =>
    forall(addressGen) { address =>
      implicit val (hs, js) = res
      hs.withCurrent { implicit hasher =>
        val v1 = GlobalSnapshotInfoV1(
          lastStateChannelSnapshotHashes = SortedMap(address -> Hash.empty),
          lastTxRefs = SortedMap.empty[Address, TransactionReference],
          balances = SortedMap(address -> Balance(100L))
        )

        val current = v1.toGlobalSnapshotInfo

        IO.pure(
          expect.all(
            current.lastStateChannelSnapshotHashes == v1.lastStateChannelSnapshotHashes,
            current.lastTxRefs == v1.lastTxRefs,
            current.balances == v1.balances,
            current.lastCurrencySnapshots.isEmpty,
            current.activeAllowSpends.exists(_.isEmpty),
            current.activeDelegatedStakes.exists(_.isEmpty)
          )
        )
      }
    }
  }

  test("GlobalSnapshotInfoV2 converts to GlobalSnapshotInfo preserving all fields") { implicit res =>
    forall(addressGen) { address =>
      implicit val (hs, js) = res
      hs.withCurrent { implicit hasher =>
        val v2 = GlobalSnapshotInfoV2(
          lastStateChannelSnapshotHashes = SortedMap(address -> Hash.empty),
          lastTxRefs = SortedMap.empty[Address, TransactionReference],
          balances = SortedMap(address -> Balance(200L)),
          lastCurrencySnapshots = SortedMap.empty,
          lastCurrencySnapshotsProofs = SortedMap.empty
        )

        val current = v2.toGlobalSnapshotInfo

        IO.pure(
          expect.all(
            current.lastStateChannelSnapshotHashes == v2.lastStateChannelSnapshotHashes,
            current.lastTxRefs == v2.lastTxRefs,
            current.balances == v2.balances,
            current.lastCurrencySnapshotsProofs == v2.lastCurrencySnapshotsProofs
          )
        )
      }
    }
  }

  test("GlobalStateProofSelector returns LegacyFormat for ordinal <= boundary") { implicit res =>
    implicit val (hs, js) = res
    hs.withCurrent { implicit hasher =>
      val selector = GlobalStateProofSelector(SnapshotOrdinal.unsafeApply(10))

      IO.pure(
        expect.all(
          selector.select(SnapshotOrdinal.unsafeApply(5)) == LegacyFormat,
          selector.select(SnapshotOrdinal.unsafeApply(10)) == LegacyFormat,
          selector.select(SnapshotOrdinal.unsafeApply(11)) == MerklePatriciaFormat,
          selector.select(SnapshotOrdinal.unsafeApply(100)) == MerklePatriciaFormat
        )
      )
    }
  }

  test("CurrencyStateProofSelector always returns LegacyFormat") { implicit res =>
    implicit val (hs, js) = res
    hs.withCurrent { implicit hasher =>
      val selector = CurrencyStateProofSelector.instance

      IO.pure(
        expect.all(
          selector.select(SnapshotOrdinal.unsafeApply(0)) == LegacyFormat,
          selector.select(SnapshotOrdinal.unsafeApply(100)) == LegacyFormat,
          selector.select(SnapshotOrdinal.unsafeApply(Long.MaxValue)) == LegacyFormat
        )
      )
    }
  }

  test("GlobalSnapshotInfo.stateProof uses selector to choose format") { implicit res =>
    forall(addressGen) { address =>
      implicit val (hs, js) = res
      hs.withCurrent { implicit hasher =>
        val info = GlobalSnapshotInfo(
          lastStateChannelSnapshotHashes = SortedMap(address -> Hash.empty),
          lastTxRefs = SortedMap.empty,
          balances = SortedMap(address -> Balance(100L)),
          lastCurrencySnapshots = SortedMap.empty,
          lastCurrencySnapshotsProofs = SortedMap.empty,
          activeAllowSpends = Some(SortedMap.empty),
          activeTokenLocks = Some(SortedMap.empty),
          tokenLockBalances = Some(SortedMap.empty),
          lastAllowSpendRefs = Some(SortedMap.empty),
          lastTokenLockRefs = Some(SortedMap.empty),
          updateNodeParameters = Some(SortedMap.empty),
          activeDelegatedStakes = Some(SortedMap.empty),
          delegatedStakesWithdrawals = Some(SortedMap.empty),
          activeNodeCollaterals = Some(SortedMap.empty),
          nodeCollateralWithdrawals = Some(SortedMap.empty),
          priceState = Some(SortedMap.empty),
          metagraphSyncData = Some(SortedMap.empty),
          retiredAllowSpendRefs = Some(SortedMap.empty)
        )

        // Test with legacy format (ordinal 5, boundary 10)
        val legacyProof = {
          implicit val legacySelector: GlobalStateProofSelector = GlobalStateProofSelector(SnapshotOrdinal.unsafeApply(10))
          info.stateProof[IO](SnapshotOrdinal.unsafeApply(5))
        }

        // Test with MPT format (ordinal 15, boundary 10)
        val mptProof = {
          implicit val mptSelector: GlobalStateProofSelector = GlobalStateProofSelector(SnapshotOrdinal.unsafeApply(10))
          info.stateProof[IO](SnapshotOrdinal.unsafeApply(15))
        }

        (legacyProof, mptProof).tupled.map {
          case (legacy, mpt) =>
            expect.all(
              legacy.mptRoot.isEmpty, // Legacy proof has no MPT root
              legacy.balancesProof =!= Hash.empty, // Legacy proof has balance proof
              mpt.mptRoot.isDefined, // MPT proof has MPT root
              mpt.balancesProof == Hash.empty // MPT proof has empty legacy fields
            )
        }
      }
    }
  }

  // ---- #10: per-sub-trie roots in the MPT state proof ----

  private def mkInfo(scsh: SortedMap[Address, Hash], balances: SortedMap[Address, Balance]): GlobalSnapshotInfo =
    GlobalSnapshotInfo(
      lastStateChannelSnapshotHashes = scsh,
      lastTxRefs = SortedMap.empty,
      balances = balances,
      lastCurrencySnapshots = SortedMap.empty,
      lastCurrencySnapshotsProofs = SortedMap.empty,
      activeAllowSpends = Some(SortedMap.empty),
      activeTokenLocks = Some(SortedMap.empty),
      tokenLockBalances = Some(SortedMap.empty),
      lastAllowSpendRefs = Some(SortedMap.empty),
      lastTokenLockRefs = Some(SortedMap.empty),
      updateNodeParameters = Some(SortedMap.empty),
      activeDelegatedStakes = Some(SortedMap.empty),
      delegatedStakesWithdrawals = Some(SortedMap.empty),
      activeNodeCollaterals = Some(SortedMap.empty),
      nodeCollateralWithdrawals = Some(SortedMap.empty),
      priceState = Some(SortedMap.empty),
      metagraphSyncData = Some(SortedMap.empty),
      retiredAllowSpendRefs = Some(SortedMap.empty)
    )

  test("sub-trie roots inert by default: MPT proof leaves per-field fields empty/None") { implicit res =>
    forall(addressGen) { address =>
      implicit val (hs, js) = res
      hs.withCurrent { implicit hasher =>
        // default selector: subTrieRootsActivationOrdinal = MaxValue (never) -> inert, today's behavior
        implicit val selector: GlobalStateProofSelector = GlobalStateProofSelector(SnapshotOrdinal.unsafeApply(0))
        val info = mkInfo(SortedMap(address -> Hash.empty), SortedMap(address -> Balance(100L)))
        info.stateProof[IO](SnapshotOrdinal.unsafeApply(10)).map { proof =>
          expect.all(
            proof.mptRoot.isDefined,
            proof.balancesProof == Hash.empty,
            proof.lastStateChannelSnapshotHashesProof == Hash.empty,
            proof.lastTxRefsProof == Hash.empty,
            proof.activeAllowSpends.isEmpty,
            proof.priceState.isEmpty,
            proof.lastGlobalSnapshotsWithCurrency.isEmpty
          )
        }
      }
    }
  }

  test("sub-trie roots activated: per-field roots populate the correct proof fields") { implicit res =>
    forall(addressGen) { address =>
      implicit val (hs, js) = res
      hs.withCurrent { implicit hasher =>
        // activation = 0: at ordinal 10 the proof is MPT-format with sub-trie roots ON
        implicit val selector: GlobalStateProofSelector =
          GlobalStateProofSelector(SnapshotOrdinal.unsafeApply(0), SnapshotOrdinal.unsafeApply(0))
        val info = mkInfo(SortedMap(address -> Hash.empty), SortedMap(address -> Balance(100L)))
        (
          info.stateProof[IO](SnapshotOrdinal.unsafeApply(10)),
          GlobalSnapshotInfo.subTrieRoots[IO](info),
          info.allStateEntries[IO].buildMpt
        ).tupled.map {
          case (proof, roots, merged) =>
            expect.all(
              // mptRoot is the merged root over all entries
              proof.mptRoot.contains(merged.value),
              // each populated proof field is wired to the matching GlobalStateFieldId sub-trie root
              proof.balancesProof == roots(GlobalStateFieldId.Balances),
              proof.lastStateChannelSnapshotHashesProof == roots(GlobalStateFieldId.LastStateChannelSnapshotHashes),
              // empty fields stay empty/None
              proof.lastTxRefsProof == Hash.empty,
              proof.activeAllowSpends.isEmpty,
              // lastCurrencySnapshotsProof is intentionally left None (MerkleRoot-typed)
              proof.lastCurrencySnapshotsProof.isEmpty
            )
        }
      }
    }
  }

  test("sub-trie roots activated: changing one field changes only that field's root") { implicit res =>
    forall(addressGen) { address =>
      implicit val (hs, js) = res
      hs.withCurrent { implicit hasher =>
        implicit val selector: GlobalStateProofSelector =
          GlobalStateProofSelector(SnapshotOrdinal.unsafeApply(0), SnapshotOrdinal.unsafeApply(0))
        val scsh = SortedMap(address -> Hash.empty)
        val info1 = mkInfo(scsh, SortedMap(address -> Balance(100L)))
        val info2 = mkInfo(scsh, SortedMap(address -> Balance(999L)))
        (
          info1.stateProof[IO](SnapshotOrdinal.unsafeApply(10)),
          info2.stateProof[IO](SnapshotOrdinal.unsafeApply(10))
        ).tupled.map {
          case (p1, p2) =>
            expect.all(
              p1.balancesProof =!= p2.balancesProof, // the changed field's root differs
              p1.lastStateChannelSnapshotHashesProof == p2.lastStateChannelSnapshotHashesProof, // unchanged field stable
              p1.mptRoot =!= p2.mptRoot // and the overall root differs too
            )
        }
      }
    }
  }
}
