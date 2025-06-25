package io.constellationnetwork.node.shared.domain.priceOracle

import java.security.KeyPair

import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.node.shared.domain.priceOracle.PricingUpdateValidator.{TooFrequentUpdate, UnauthorizedMetagraph}
import io.constellationnetwork.schema.artifact.PricingUpdate
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.priceOracle.TokenPair.DAG_USD
import io.constellationnetwork.schema.priceOracle.{PriceFraction, PriceRecord}
import io.constellationnetwork.schema.{GlobalSnapshotInfo, NonNegFraction}
import io.constellationnetwork.security.key.ops._
import io.constellationnetwork.security.{KeyPairGenerator, SecurityProvider}

import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}
import weaver.MutableIOSuite

object PricingUpdateValidatorSuite extends MutableIOSuite {

  override type Res = (KeyPair, KeyPair)

  override def sharedResource: Resource[IO, Res] =
    for {
      implicit0(sp: SecurityProvider[IO]) <- SecurityProvider.forAsync[IO]
      kp1 <- KeyPairGenerator.makeKeyPair[IO].asResource
      kp2 <- KeyPairGenerator.makeKeyPair[IO].asResource
    } yield (kp1, kp2)

  test("metagraph whitelists") { res =>
    val (kp1, kp2) = res

    val address1 = kp1.getPublic.toAddress
    val address2 = kp2.getPublic.toAddress

    val allowedMetagraphIds = List(address1).some

    val validator = PricingUpdateValidator.make[IO](allowedMetagraphIds, NonNegLong(0))

    for {
      update1 <- pricingUpdate(1)
      update2 <- pricingUpdate(2)
      (accepted, rejected) <- validator.validateReturningAcceptedAndRejected(
        Map(address1 -> List(update1), address2 -> List(update2)),
        GlobalSnapshotInfo.empty,
        EpochProgress.MinValue
      )
    } yield expect.all(accepted == List(update1), rejected == List((update2, List(UnauthorizedMetagraph(address2)))))
  }

  test("too frequent updates") { res =>
    val (kp1, kp2) = res

    val address1 = kp1.getPublic.toAddress
    val address2 = kp2.getPublic.toAddress

    val validator = PricingUpdateValidator.make[IO](None, NonNegLong(2))

    for {
      update1 <- pricingUpdate(1)
      update2 <- pricingUpdate(2)
      update3 <- pricingUpdate(3)
      snapshotInfo = GlobalSnapshotInfo.empty.copy(priceState =
        SortedMap(DAG_USD -> PriceRecord(update3, update3, update3, PosInt(1), EpochProgress.MinValue, EpochProgress(NonNegLong(3)))).some
      )
      (accepted, rejected) <- validator.validateReturningAcceptedAndRejected(
        Map(address1 -> List(update1), address2 -> List(update2)),
        snapshotInfo,
        EpochProgress(NonNegLong(4))
      )
    } yield
      expect.all(
        accepted == List(),
        rejected == List(
          (update1, List(TooFrequentUpdate(EpochProgress(NonNegLong(3)), EpochProgress(NonNegLong(4))))),
          (update2, List(TooFrequentUpdate(EpochProgress(NonNegLong(3)), EpochProgress(NonNegLong(4)))))
        )
      )
  }

  private def pricingUpdate(n: Long): IO[PricingUpdate] =
    NonNegFraction(n, 1L).map(value => PricingUpdate(price = PriceFraction(DAG_USD, value)))

}
