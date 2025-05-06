package io.constellationnetwork.schema

import io.constellationnetwork.schema.priceOracle.TokenPair.DAG_USD
import io.constellationnetwork.schema.priceOracle._

import io.circe.{KeyDecoder, KeyEncoder}
import weaver.FunSuite
import weaver.scalacheck.Checkers

object TokenPairSuite extends FunSuite with Checkers {

  test("DAG keyEncoder/keyDecode") {
    val tokenId: TokenId = DAG
    val encoded = KeyEncoder[TokenId].apply(tokenId)
    val decoded = KeyDecoder[TokenId].apply(encoded)
    expect.all(encoded == "DAG" && decoded.contains(DAG))
  }

  test("USD keyEncoder/keyDecode") {
    val tokenId: TokenId = USD
    val encoded = KeyEncoder[TokenId].apply(tokenId)
    val decoded = KeyDecoder[TokenId].apply(encoded)
    expect.all(encoded == "USD" && decoded.contains(USD))
  }

  test("DAG_USD keyEncoder/keyDecode") {
    val tokenPair: TokenPair = DAG_USD
    val encoded = KeyEncoder[TokenPair].apply(tokenPair)
    val decoded = KeyDecoder[TokenPair].apply(encoded)
    expect.all(encoded == "DAG::USD" && decoded.contains(DAG_USD))
  }

}
