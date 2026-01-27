package io.constellationnetwork.schema

import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.generators.addressGen
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
          metagraphSyncData = Some(SortedMap.empty)
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
}
