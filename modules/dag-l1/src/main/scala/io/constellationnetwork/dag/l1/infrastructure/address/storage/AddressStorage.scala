package io.constellationnetwork.dag.l1.infrastructure.address.storage

import cats.effect.Async
import cats.effect.kernel.Ref
import cats.syntax.functor._

import io.constellationnetwork.dag.l1.domain.address.storage.AddressStorage
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance

import eu.timepit.refined.auto._

object AddressStorage {
  def make[F[_]: Async]: F[AddressStorage[F]] =
    Ref.of[F, Map[Address, Balance]](Map.empty).map(make(_))

  def make[F[_]: Async](balances: Map[Address, Balance]): F[AddressStorage[F]] =
    Ref.of[F, Map[Address, Balance]](balances).map(make(_))

  def make[F[_]: Async](balances: Ref[F, Map[Address, Balance]]): AddressStorage[F] =
    new AddressStorage[F] {
      def getState: F[Map[Address, Balance]] =
        balances.get

      def getBalance(address: Address): F[Balance] =
        balances.get.map(_.getOrElse(address, Balance.empty))

      def updateBalances(addressBalances: Map[Address, Balance]): F[Unit] =
        balances.update(_ ++ addressBalances)

      def clean: F[Unit] =
        balances.set(Map.empty)
    }
}
