package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.SpendTransaction
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.swap._
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import weaver.MutableIOSuite

/** PROT-1691: a metagraph's `info.activeAllowSpends` necessarily lags the global layer, because a metagraph only learns that its
  * SpendAction was accepted from the global snapshot that accepted it. Folding that lagging self-report back into the global layer's own
  * map re-adds references the global layer has already retired, and a re-added reference can be presented and settled again.
  */
object AllowSpendAcceptanceSuite extends MutableIOSuite {

  type Res = (JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  def sharedResource: Resource[IO, Res] = for {
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
    h = Hasher.forJson[IO]
  } yield (j, h, sp)

  private val expiry = EpochProgress(500L)
  private val currentEpoch = EpochProgress(10L)

  // `amount` only varies so that the two allow-spends in a test hash differently.
  private def allowSpend(source: Address, destination: Address, amount: Long): AllowSpend =
    AllowSpend(
      source,
      destination,
      None,
      SwapAmount(PosLong.unsafeFrom(amount)),
      AllowSpendFee(1L),
      AllowSpendReference.empty,
      expiry,
      List(destination)
    )

  test("does not re-add a retired reference reported again by a lagging metagraph snapshot") { res =>
    implicit val (_, h, sp) = res

    for {
      userKey <- KeyPairGenerator.makeKeyPair[IO]
      ammKey <- KeyPairGenerator.makeKeyPair[IO]
      metagraphKey <- KeyPairGenerator.makeKeyPair[IO]

      user = userKey.getPublic.toAddress
      amm = ammKey.getPublic.toAddress
      metagraphId = metagraphKey.getPublic.toAddress

      replayed <- Signed.forAsyncHasher(allowSpend(user, amm, 100L), userKey)
      untouched <- Signed.forAsyncHasher(allowSpend(user, amm, 200L), userKey)
      replayedHash <- replayed.toHashed.map(_.hash)

      // The metagraph keeps reporting both allow-spends until it syncs the global snapshot that spent the first one.
      metagraphReport = SortedMap(metagraphId -> SortedMap(user -> SortedSet(replayed, untouched)))
      spendTxn = SpendTransaction(replayedHash.some, None, SwapAmount(100L), user, amm)

      // Round 1: the spend action is accepted, so the reference is retired.
      (afterSpend, retiredAfterSpend) <- AllowSpendAcceptance.acceptAllowSpends[IO](
        currentEpoch,
        metagraphReport,
        SortedMap.empty,
        SortedMap.empty,
        List(spendTxn),
        SortedMap.empty,
        preventAllowSpendResurrection = true
      )

      // Round 2: the metagraph has not synced yet and reports the spent allow-spend again. No spend action this round.
      (afterReplay, _) <- AllowSpendAcceptance.acceptAllowSpends[IO](
        currentEpoch,
        metagraphReport,
        SortedMap.empty,
        afterSpend,
        List.empty,
        retiredAfterSpend,
        preventAllowSpendResurrection = true
      )

      activeAfterSpend = afterSpend
        .getOrElse(metagraphId.some, SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])
        .getOrElse(user, SortedSet.empty[Signed[AllowSpend]])
      activeAfterReplay = afterReplay
        .getOrElse(metagraphId.some, SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])
        .getOrElse(user, SortedSet.empty[Signed[AllowSpend]])
    } yield
      expect(!activeAfterSpend.contains(replayed)) &&
        expect(activeAfterSpend.contains(untouched)) &&
        expect(
          retiredAfterSpend
            .getOrElse(metagraphId.some, SortedMap.empty[Address, SortedMap[Hash, EpochProgress]])
            .getOrElse(user, SortedMap.empty[Hash, EpochProgress])
            .contains(replayedHash)
        ) &&
        expect(!activeAfterReplay.contains(replayed)) &&
        expect(activeAfterReplay.contains(untouched))
  }

  test("below the activation ordinal the lagging report still resurrects the reference") { res =>
    implicit val (_, h, sp) = res

    for {
      userKey <- KeyPairGenerator.makeKeyPair[IO]
      ammKey <- KeyPairGenerator.makeKeyPair[IO]
      metagraphKey <- KeyPairGenerator.makeKeyPair[IO]

      user = userKey.getPublic.toAddress
      amm = ammKey.getPublic.toAddress
      metagraphId = metagraphKey.getPublic.toAddress

      replayed <- Signed.forAsyncHasher(allowSpend(user, amm, 100L), userKey)
      replayedHash <- replayed.toHashed.map(_.hash)

      metagraphReport = SortedMap(metagraphId -> SortedMap(user -> SortedSet(replayed)))
      spendTxn = SpendTransaction(replayedHash.some, None, SwapAmount(100L), user, amm)

      (afterSpend, retiredAfterSpend) <- AllowSpendAcceptance.acceptAllowSpends[IO](
        currentEpoch,
        metagraphReport,
        SortedMap.empty,
        SortedMap.empty,
        List(spendTxn),
        SortedMap.empty,
        preventAllowSpendResurrection = false
      )

      (afterReplay, _) <- AllowSpendAcceptance.acceptAllowSpends[IO](
        currentEpoch,
        metagraphReport,
        SortedMap.empty,
        afterSpend,
        List.empty,
        retiredAfterSpend,
        preventAllowSpendResurrection = false
      )

      activeAfterReplay = afterReplay
        .getOrElse(metagraphId.some, SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])
        .getOrElse(user, SortedSet.empty[Signed[AllowSpend]])
    } yield
      expect(retiredAfterSpend.isEmpty) &&
        expect(activeAfterReplay.contains(replayed))
  }

  test("forgets a retired reference once its allow-spend can no longer be presented") { res =>
    implicit val (_, h, sp) = res

    for {
      userKey <- KeyPairGenerator.makeKeyPair[IO]
      ammKey <- KeyPairGenerator.makeKeyPair[IO]
      metagraphKey <- KeyPairGenerator.makeKeyPair[IO]

      user = userKey.getPublic.toAddress
      amm = ammKey.getPublic.toAddress
      metagraphId = metagraphKey.getPublic.toAddress

      spent <- Signed.forAsyncHasher(allowSpend(user, amm, 100L), userKey)
      spentHash <- spent.toHashed.map(_.hash)

      retired = SortedMap(metagraphId.some -> SortedMap(user -> SortedMap(spentHash -> expiry)))

      // At an epoch past the allow-spend's lastValidEpochProgress the unexpired filter rejects it on its own,
      // so keeping the entry would grow the ledger without bound.
      (_, retiredAfter) <- AllowSpendAcceptance.acceptAllowSpends[IO](
        EpochProgress(NonNegLong.unsafeFrom(expiry.value.value + 1L)),
        SortedMap(metagraphId -> SortedMap(user -> SortedSet(spent))),
        SortedMap.empty,
        SortedMap.empty,
        List.empty,
        retired,
        preventAllowSpendResurrection = true
      )
    } yield
      expect(
        !retiredAfter
          .getOrElse(metagraphId.some, SortedMap.empty[Address, SortedMap[Hash, EpochProgress]])
          .getOrElse(user, SortedMap.empty[Hash, EpochProgress])
          .contains(spentHash)
      )
  }

  test("retires references per currency, not across currencies") { res =>
    implicit val (_, h, sp) = res

    for {
      userKey <- KeyPairGenerator.makeKeyPair[IO]
      ammKey <- KeyPairGenerator.makeKeyPair[IO]
      metagraphKey <- KeyPairGenerator.makeKeyPair[IO]

      user = userKey.getPublic.toAddress
      amm = ammKey.getPublic.toAddress
      metagraphId = metagraphKey.getPublic.toAddress

      globalAllowSpend <- Signed.forAsyncHasher(allowSpend(user, amm, 100L), userKey)
      globalHash <- globalAllowSpend.toHashed.map(_.hash)

      // The same hash marked retired under a metagraph must not suppress the global layer's own allow-spend.
      retiredUnderMetagraph = SortedMap(metagraphId.some -> SortedMap(user -> SortedMap(globalHash -> expiry)))

      (updated, _) <- AllowSpendAcceptance.acceptAllowSpends[IO](
        currentEpoch,
        SortedMap.empty,
        SortedMap(user -> SortedSet(globalAllowSpend)),
        SortedMap.empty,
        List.empty,
        retiredUnderMetagraph,
        preventAllowSpendResurrection = true
      )

      activeGlobal = updated
        .getOrElse(none[Address], SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])
        .getOrElse(user, SortedSet.empty[Signed[AllowSpend]])
    } yield expect(activeGlobal.contains(globalAllowSpend))
  }

  test("does not re-add a retired reference on the global layer either") { res =>
    implicit val (_, h, sp) = res

    for {
      userKey <- KeyPairGenerator.makeKeyPair[IO]
      ammKey <- KeyPairGenerator.makeKeyPair[IO]

      user = userKey.getPublic.toAddress
      amm = ammKey.getPublic.toAddress

      retiredSpend <- Signed.forAsyncHasher(allowSpend(user, amm, 100L), userKey)
      retiredHash <- retiredSpend.toHashed.map(_.hash)

      retired = SortedMap(none[Address] -> SortedMap(user -> SortedMap(retiredHash -> expiry)))

      (updated, _) <- AllowSpendAcceptance.acceptAllowSpends[IO](
        currentEpoch,
        SortedMap.empty,
        SortedMap(user -> SortedSet(retiredSpend)),
        SortedMap.empty,
        List.empty,
        retired,
        preventAllowSpendResurrection = true
      )

      activeGlobal = updated
        .getOrElse(none[Address], SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])
        .getOrElse(user, SortedSet.empty[Signed[AllowSpend]])
    } yield expect(!activeGlobal.contains(retiredSpend))
  }
}
