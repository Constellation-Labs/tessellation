package io.constellationnetwork.node.shared.infrastructure.statechannel

import cats.data.NonEmptySet
import cats.syntax.option._

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.env.AppEnvironment._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

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
          Address("DAG7ChnhUF7uKgn8tXy45aj4zn9AFuhaZr8VXY43") ->
            NonEmptySet.of(
              "db6ed7baf24ecc7276cf10cc86a4a62e18064293415bb5287e0b94277cc8e5ea7ef6cecf561c12f300507893892267e22f1f4b2e2326a03edf00acf38be3c2ea",
              "ebb46bab1dc37a0cb74b852480b472c029146c577caecdab76acc17c469e0ca9f4d2c32a953f1328f3d5d2091b41427542ed373d9cc78b8974a251fd95586b18",
              "ef28578bb52f91cd9b976a3475962c153f7d3ce1c7b1b034920dcfc0cc7f251b4a5e6509b69859d6e7ed8e7b6b952103215bb2c96eb711ebdfd4a61c432e5ac8"
            ),
          Address("DAG7fwxZJpqBpXeHqjomVkvUfC9NgZeQ11qjmB5e") ->
            NonEmptySet.of(
              "77f7f6ea96cee2eb7fe4e577eddc20e3f3ba6055095e1c0157974bb3e484b285fbc66e00a91b350ae0a36f50da722fe18e1a33ee91f8e73bb5eaf4684f1bc194",
              "ddec0a0d2742355565fdf7e2c2b0439cecf25aee55fe6426174fb42e122062d01e61292f526f9852a09a9ac21edcdd252dac229cbac645d7899c435695956c26",
              "3cbf6e802f2e7cf580f1498a74b9eb4d6522353522bfe1a5cb33cef8bc64e7a8a536bb12257d7d77f1b1fd440f2de8952b02aa0ab6a389b0ef5c78a1e6149fe1"
            ),
          Address("DAG6oJ5BgUbxjeSYKxgjT1YEUZ3QBS1MN5XkstfT") ->
            NonEmptySet.of(
              "65b0c6ac3d0df47d7e7c275ac2d439d11dd73d67bca59cc9771ab7868d4a3e9e8a10661541932af394ca741765bd4d5807f030a3217553db75f050f7e65193fd",
              "3250031672be56b6901447db90314914213bd526594e01064b89361da801d9da54c91eabc3e4a86b60f2a1f0f59ae58155ff9f9671b7a3cb8761ea97d86e7303",
              "034f080a8cb94c3851bc2bb41c8d3cc3052d2241fb29e86657bde5c1f60143611a7012ff40142d40f62204a73da2b85f751cb2f1c540f45eef6b23ee7ddd4b7d"
            ),
          Address("DAG06z64ifT2HzXoHfMexRfrcnpYFEwMqjFiPKze") ->
            NonEmptySet.of(
              "d7f274254dd70558ca8a30745a371efccf5f9f41e00aa1ff1760218c132b201c5d000b23889cd9c0d6e978d690c8852e9b0d321376c690bd287e0e771f3ba6c0",
              "741b1977253e08cec2fee737637011b843d0981820b06362781928ebb227821064595454e3d91ea5811cd326422f827d499c16b232fa9a06c65e965f1a767a67",
              "04917e4b00c63a67a347120c0cfc9e7aa6b25ad6896f2fb5e5049d23e958a60c7b4ee355a26ef1b0409f13401ceb7ed125b16dc2852842ee703cabf0cb9b5805"
            ),
          Address("DAG7Ghth1WhWK83SB3MtXnnHYZbCsmiRTwJrgaW1") ->
            NonEmptySet.of(
              "660473b202ef99387e4ac1316932a845d0369474d242889c9679f330aad517aa1193261f602629c64ae59dead7a5912fd7b019877a1432a568eec6884f6db80e",
              "e3302c117d571d3f4fe9884776fac09fbd25731dc6df83d3c1cdfbcbb9e0bbabf732b445826ff052f378df8ef29737dc1cf0bd66d5aec4ae1553fe323939a856",
              "5cc3dce875fad02cff7af28b64f79d8db8fe8b85d9369f124618f69f478b992538318aaf07327ea9384048fb2c6a222e887f8325ad02b255c97052d32c76daf3"
            ),
          // USDC
          Address("DAG0S16WDgdAvh8VvroR6MWLdjmHYdzAF5S181xh") ->
            NonEmptySet.of(
              "4474bbae5936a48ef951ff3a72f655af556c4b07e94a1e64a4e05dd05e418cfefed4f7ca01a9664052b8618ed70078d8a57e209ea7c591002fb94f2acacd0e35",
              "154090bdd08a05217acb8b7d4f5e56abef13120e688818edbffe4a55460a0c7445170033d81b45f0596f1a8330265c79567a89c69f52103b4eb4e89eb3017268",
              "afbc97eeb40a5e89752924c885c39c5b00a8f9ff4929df59322b07e2613c3b8abcea49aac29783c0494f45e70697bf1ca75fb11d10f37d23d7fc1e4a7bfecd09"
            ),
          // CYBERLETE
          Address("DAG0rgR8sdn8u2YBYb5Ftjy4zmuqUX3v9XsE2j94") ->
            NonEmptySet.of(
              "bb16637e74281727fcee8a32f9b4df2af8c84005864ad7e44d34fcd3215e8bf95745e812005523d16b95c2d8f2dfad8e023dd13bd18b379af461d8ab04664ad7",
              "5aea1023e4c236ca832943de7ae5f2e5a4b6cb40b750cec34207c8f9b8eb2671566c9b03de29d7f7330f94c7117b21efc505534232ea1b6c3e13b85ac6e53856",
              "b29da8e4d9d286696903db4f2d0bf5a7ede47e4d4e80617faefbf94de8c198e3749f4fe600dfeae378d2d0b4ac73f2d4bc4bfdb2abd3fb7e4dc3859ae4a1ffd4"
            ),
          // PACASWAP
          Address("DAG7X5idd4aLfp4XC6WQdG1eDfR3LGPVEwtUUB2W") ->
            NonEmptySet.of(
              "07599336019bbe8ab5a8beae4f83f4b5ca3ca2001cd32924431e393489df19e098582548ca83eef2a88f98bd32befe8f166aea4f1db31212f59f6a8092db91b6",
              "2551a64034e3cb08c94986ade958541cd12cc64a9cbfb24016cba7284ef68aa5a6712dbbe21a9e717dbede1d893bf76a9fe98935d3ab315271ecb922453f8526",
              "f169f8f51bc75948f4144f5f137b4f993a7aa1498febfe11e40ac3eb5f5322b93377b6a2609ea3daff5209d3c4809e70d4169c80732bb8bf23877977eabc986a"
            ),
          // DeD
          Address("DAG0eQr94qUQSUhmYGNXt6CoBKWu5K6htvRMGC6M") ->
            NonEmptySet.of(
              "dbb8bd3f03e132717472ff3bdc2f55cfb9886e7615e500751a5ece822f2842ad37ecaaafe391892413f5d9471134a01c56c31926def04615df3d40288c4cee0f",
              "e19449faa3f422a0a0e6954a1ccdf2ba023efadf0ef83f6c33c82f66d7fde81d7670e5aaf294cf1d9d61554095bb8b2d0508f2ecf157f8a5cfc73c830a397768",
              "79c986a5fa6277723783c88c4f829dbff1faa2359566a1454a533c42f4142ea4491fdf30d7cab26b5c4a148d07468e41f7430f768c5d788589a345b380e2bfe0"
            ),
          // Price Oracle
          Address("DAG6xfTWbFMjp1AmatNCWzuunH1V4XTTJsRKCHLJ") ->
            NonEmptySet.of(
              "73a9257c176e1c5aee4d2d9d576059ac0d5305bcc26da3512e8242a0de6accd1aa8efd3505b1e0b46b12dfd780e9711ba77b21b7ac3fedb60b865dc21087a49f",
              "75d8f472fb2bebfcefbb27a46cb60aeae1a32806c9f3f40a405d4eaf1c44e8041febf5e6eb9e6f525d6142c7df87304b9fe011863c6d8ed121fa6fcae7d5ca66",
              "e47e0ac3ebc393b61e267aa4189f7fdc36c48440b1fc0a6bad375945cabfd41ab3e7800ae1d22d80d2d4a3b0902df6aeb293b803490bf8d777da3894833f42fb"
            )
        ).some
    }

  private def allowanceMap(tuples: (Address, NonEmptySet[String])*): Map[Address, NonEmptySet[PeerId]] =
    tuples.map { case (addr, ids) => addr -> ids.map(s => PeerId(Hex(s))) }.toMap
}
