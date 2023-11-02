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
          Address("DAG8gMagrwoJ4nAMjbGx17WB5D6nqBEPZYChc3zH"),
          NonEmptySet.of(
            "1f1494da3bf0fdf70faff3fd21cebcd322b2b4d0abc5107924a0a70239afe12c480c9ca9f70ec125bee15781e93310f2a5d726c0f26b287785d009ef93bcaa77",
            "1ec3c11867e3cd984a31db77e053c4105c78115ae604503fd9b0ef03399efd41464ac6efdf54cb3cdaa480be5262e7bd3fd2e1b6cc6bdc6dc3cee94f31c90856",
            "3fd28a8c11a56434b1806abc8f244a0db8896f3eb53951a9712a9e7085af88097290ef8169752b21a0f62f7ae4c5002db9cb46ba791a3253caf41cfa9cb3135a"
          )
        ).some

      case Integrationnet =>
        allowanceMap(
          Address("DAG5kfY9GoHF1CYaY8tuRJxmB3JSzAEARJEAkA2C"),
          NonEmptySet.of(
            "a2496d09b7325f7e96d0f774c8fd45670779ec0614b672db23d2cf8342c13aaa6a64146996f8aed365eb12412c9b39f2eade3e45f610464212b8f69a660271d5",
            "49496e22cffa3314958aa12fc16c658ce5ed0a8da032823a6100c39d7ef5198221888350c1d40cd35c8892d779e9d50b4c8a0f542168c6a586967b1c6dd5b153",
            "d741b547225b6ba6f1ba38be192ab7550b7610ef54e7fee88a9666b79a12a6741d1565241fba5c2a812be66edd878824f927a42430ffba48fa0bd0264a5483bf"
          )
        ).some

      case Mainnet => Map.empty[Address, NonEmptySet[PeerId]].some
    }

  private def allowanceMap(metagraphId: Address, peerIdValues: NonEmptySet[String]): Map[Address, NonEmptySet[PeerId]] =
    Map(metagraphId -> peerIdValues.map(s => PeerId(Hex(s))))
}
