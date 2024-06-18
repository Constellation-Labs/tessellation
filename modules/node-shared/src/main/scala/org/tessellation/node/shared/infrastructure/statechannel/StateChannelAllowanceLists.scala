package org.tessellation.node.shared.infrastructure.statechannel

import cats.data.NonEmptySet
import cats.syntax.option._

import org.tessellation.env.AppEnvironment
import org.tessellation.env.AppEnvironment._
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

      case Testnet => none

      case Integrationnet => none

      case Mainnet =>
        allowanceMap(
          Address("DAG0CyySf35ftDQDQBnd1bdQ9aPyUdacMghpnCuM") ->
            NonEmptySet.of(
              "ced1b13081d75a8c2e1463a8c2ff09f1ea14ff7af3265bcd3d4acfa3290626f965001a7ed6dbf2a748145ddecf1eb8ffeddf42d29dee3541a769601ea4cbba02",
              "c54ccbea2a8d3c989281a51e7e41298e1e0f668c0c8112f1837944d137744d0c38c0a493d0c45ddfe5e0489bef180bccfcd654b250a539116e83965b90e0413c",
              "f27242529710fd85a58fcacba31e34857e9bc92d622b4ca856c79a12825bca8fa133dd5697fd650d3caedc93d1524670dd1150b266505c1350d8aafce5f364f8"
            ),
          Address("DAG5U3EdNmiGwfZMpHVwyQphhbpEZ2LXFJFwcvZc") ->
            NonEmptySet.of(
              "db6ed7baf24ecc7276cf10cc86a4a62e18064293415bb5287e0b94277cc8e5ea7ef6cecf561c12f300507893892267e22f1f4b2e2326a03edf00acf38be3c2ea",
              "ebb46bab1dc37a0cb74b852480b472c029146c577caecdab76acc17c469e0ca9f4d2c32a953f1328f3d5d2091b41427542ed373d9cc78b8974a251fd95586b18",
              "ef28578bb52f91cd9b976a3475962c153f7d3ce1c7b1b034920dcfc0cc7f251b4a5e6509b69859d6e7ed8e7b6b952103215bb2c96eb711ebdfd4a61c432e5ac8"
            )
        ).some
    }

  private def allowanceMap(tuples: (Address, NonEmptySet[String])*): Map[Address, NonEmptySet[PeerId]] =
    tuples.map { case (addr, ids) => addr -> ids.map(s => PeerId(Hex(s))) }.toMap
}
