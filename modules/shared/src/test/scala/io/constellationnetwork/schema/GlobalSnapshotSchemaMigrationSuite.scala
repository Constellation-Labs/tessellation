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

  type Res = HasherSelector[IO]

  override def sharedResource: Resource[IO, Res] =
    for {
      implicit0(kryo: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
      implicit0(json: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].toResource
    } yield
      HasherSelector.forSync[IO](
        Hasher.forJson[IO],
        Hasher.forKryo[IO],
        hashSelect = new HashSelect { def select(ordinal: SnapshotOrdinal): HashLogic = JsonHash }
      )

  test("GlobalSnapshotInfoV1 converts to GlobalSnapshotInfo preserving core fields") { implicit res =>
    forall(addressGen) { address =>
      res.withCurrent { implicit hasher =>
        val v1 = GlobalSnapshotInfoV1(
          lastStateChannelSnapshotHashes = SortedMap(address -> Hash.empty),
          lastTxRefs = SortedMap.empty[Address, TransactionReference],
          balances = SortedMap(address -> Balance(100L))
        )

        val current = GlobalSnapshotInfoV1.toGlobalSnapshotInfo(v1)

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
      res.withCurrent { implicit hasher =>
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

  test("GlobalSnapshotInfoV3 converts to GlobalSnapshotInfo preserving all fields") { implicit res =>
    forall(addressGen) { address =>
      res.withCurrent { implicit hasher =>
        val v3 = GlobalSnapshotInfoV3(
          lastStateChannelSnapshotHashes = SortedMap(address -> Hash.empty),
          lastTxRefs = SortedMap.empty[Address, TransactionReference],
          balances = SortedMap(address -> Balance(300L)),
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

        val current = v3.toGlobalSnapshotInfo

        IO.pure(
          expect.all(
            current.lastStateChannelSnapshotHashes == v3.lastStateChannelSnapshotHashes,
            current.balances == v3.balances,
            current.activeAllowSpends == v3.activeAllowSpends,
            current.activeDelegatedStakes == v3.activeDelegatedStakes
          )
        )
      }
    }
  }

  test("GlobalSnapshotStateProof toLegacyProof returns empty proof") { implicit res =>
    res.withCurrent { implicit hasher =>
      val current = GlobalSnapshotStateProof(
        stateRoot = Hash("abc")
      )

      val legacy = current.toLegacyProof

      IO.pure(
        expect.all(
          legacy.lastStateChannelSnapshotHashesProof == Hash.empty,
          legacy.lastTxRefsProof == Hash.empty,
          legacy.balancesProof == Hash.empty,
          legacy.lastCurrencySnapshotsProof.isEmpty
        )
      )
    }
  }

  test("GlobalSnapshotStateProof fromLegacyProof converts V2 to current") { implicit res =>
    res.withCurrent { implicit hasher =>
      val v2 = GlobalSnapshotStateProofV2(
        lastStateChannelSnapshotHashesProof = Hash("abc"),
        lastTxRefsProof = Hash("def"),
        balancesProof = Hash("ghi"),
        lastCurrencySnapshotsProof = None,
        activeAllowSpends = None,
        activeTokenLocks = None,
        tokenLockBalances = None,
        lastAllowSpendRefs = None,
        lastTokenLockRefs = None,
        updateNodeParameters = None,
        activeDelegatedStakes = None,
        delegatedStakesWithdrawals = None,
        activeNodeCollaterals = None,
        nodeCollateralWithdrawals = None,
        priceState = None,
        lastGlobalSnapshotsWithCurrency = None
      )

      val current = GlobalSnapshotStateProof.fromLegacyProof(v2)

      IO.pure(
        expect(current.stateRoot == Hash.empty)
      )
    }
  }

  test("stateProofFor with JsonHash produces MPT-based proof") { implicit res =>
    forall(addressGen) { address =>
      res.withCurrent { implicit hasher =>
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

        val ordinal = SnapshotOrdinal.unsafeApply(1000000L)

        info.stateProofFor[IO](JsonHash, ordinal).map { proof =>
          expect(proof.stateRoot =!= Hash.empty)
        }
      }
    }
  }

  test("stateProofFor with KryoHash produces legacy proof converted to current") { implicit res =>
    forall(addressGen) { address =>
      res.withCurrent { implicit hasher =>
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

        val ordinal = SnapshotOrdinal.unsafeApply(100L)

        info.stateProofFor[IO](KryoHash, ordinal).map { proof =>
          expect(proof.stateRoot == Hash.empty)
        }
      }
    }
  }
}
