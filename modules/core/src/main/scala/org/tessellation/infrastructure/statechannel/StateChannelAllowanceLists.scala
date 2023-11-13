package org.tessellation.infrastructure.statechannel

import cats.data.NonEmptySet
import cats.syntax.option._

import org.tessellation.cli.AppEnvironment
import org.tessellation.cli.AppEnvironment._
import org.tessellation.schema.address.Address
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.hex.Hex

import eu.timepit.refined.auto._

object StateChannelAllowanceLists {

  // Allowance list map is comprised of:
  // CL0 metagraph ID -> Set(peer ids from CL0 cluster info)

  def get(env: AppEnvironment): Option[Map[Address, NonEmptySet[PeerId]]] =
    env match {
      case Dev => none

      case Testnet =>
        allowanceMap(
          Address("DAG8gMagrwoJ4nAMjbGx17WB5D6nqBEPZYChc3zH") ->
            NonEmptySet.of(
              "1f1494da3bf0fdf70faff3fd21cebcd322b2b4d0abc5107924a0a70239afe12c480c9ca9f70ec125bee15781e93310f2a5d726c0f26b287785d009ef93bcaa77",
              "1ec3c11867e3cd984a31db77e053c4105c78115ae604503fd9b0ef03399efd41464ac6efdf54cb3cdaa480be5262e7bd3fd2e1b6cc6bdc6dc3cee94f31c90856",
              "3fd28a8c11a56434b1806abc8f244a0db8896f3eb53951a9712a9e7085af88097290ef8169752b21a0f62f7ae4c5002db9cb46ba791a3253caf41cfa9cb3135a"
            ),
          Address("DAG3spyM8ZguHxPaJC9K6gCPus2HqPwz5KtguaBK") ->
            NonEmptySet.of(
              "a2dcfae447ee1f90803c62083c614357fedab2e8cc21fcd7adff7f3fd3f435b6f259392b7d888758ea508d3442323ef837267dfd4e3421511237ed1f56d54faf",
              "189f68052ccdec7486c7406b1903e8dfa0c2caabe7586861aba8e3009fc898eadd50297c84525cd074bc4a635f8e3e6ac2517ba5993465946c512dbd1a9ca783",
              "e75dce5920c141d38be8eef0fca5042e7ca5c26becc4d46cc189e180cab60d5ff1070daa109c9425ca74ac8823ff426bbd4597fcdf19d06c8af29bdebb34d821"
            )
        ).some

      case Integrationnet =>
        allowanceMap(
          Address("DAG5kfY9GoHF1CYaY8tuRJxmB3JSzAEARJEAkA2C") ->
            NonEmptySet.of(
              "a2496d09b7325f7e96d0f774c8fd45670779ec0614b672db23d2cf8342c13aaa6a64146996f8aed365eb12412c9b39f2eade3e45f610464212b8f69a660271d5",
              "49496e22cffa3314958aa12fc16c658ce5ed0a8da032823a6100c39d7ef5198221888350c1d40cd35c8892d779e9d50b4c8a0f542168c6a586967b1c6dd5b153",
              "d741b547225b6ba6f1ba38be192ab7550b7610ef54e7fee88a9666b79a12a6741d1565241fba5c2a812be66edd878824f927a42430ffba48fa0bd0264a5483bf"
            ),
          Address("DAG4dWrdALPQmvF5UBpuXrqdkMHea1H5f7rjb4qY") ->
            NonEmptySet.of(
              "45cd9d2bc61385a91826d0780893ce85e4c6b19f48142d512757b28102b79da540ee9e71171f6efad679531bd1db67d74b07e9a395730f5f5c510e11f805cc66",
              "55d58805b882720503e3c4469936cf9ec7a2d28a81ebb0ec51ffb302d24a470e63eb7a77b5641ccef703a03dca0e0f38403ae4aebcd20a32f5a0c3cdc6aa430f",
              "6833e2347a019c45d4c6e02e49330ac65a5add58309bf6de4784cbb8aee3cceebf3ecca3abfed3f0b9383a4697b3979ae5addb36f16abf4d1b63fdca95cc68ac"
            )
        ).some

      case Mainnet =>
        allowanceMap(
          Address("DAG0CyySf35ftDQDQBnd1bdQ9aPyUdacMghpnCuM") ->
            NonEmptySet.of(
              "ced1b13081d75a8c2e1463a8c2ff09f1ea14ff7af3265bcd3d4acfa3290626f965001a7ed6dbf2a748145ddecf1eb8ffeddf42d29dee3541a769601ea4cbba02",
              "c54ccbea2a8d3c989281a51e7e41298e1e0f668c0c8112f1837944d137744d0c38c0a493d0c45ddfe5e0489bef180bccfcd654b250a539116e83965b90e0413c",
              "f27242529710fd85a58fcacba31e34857e9bc92d622b4ca856c79a12825bca8fa133dd5697fd650d3caedc93d1524670dd1150b266505c1350d8aafce5f364f8"
            )
        ).some
    }

  private def allowanceMap(tuples: (Address, NonEmptySet[String])*): Map[Address, NonEmptySet[PeerId]] =
    tuples.map { case (addr, ids) => addr -> ids.map(s => PeerId(Hex(s))) }.toMap
}
