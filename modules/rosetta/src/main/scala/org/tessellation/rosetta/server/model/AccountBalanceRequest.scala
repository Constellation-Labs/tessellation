/**
  * Rosetta
  * Build Once. Integrate Your Blockchain Everywhere.
  *
  * The version of the OpenAPI document: 1.4.12
  * Contact: team@openapitools.org
  *
  * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech).
  * https://openapi-generator.tech
  */

package org.tessellation.rosetta.server.model

case class AccountBalanceRequest(
  networkIdentifier: NetworkIdentifier,
  accountIdentifier: AccountIdentifier,
  blockIdentifier: Option[PartialBlockIdentifier],
  /* In some cases, the caller may not want to retrieve all available balances for an AccountIdentifier. If the currencies field is populated, only balances for the specified currencies will be returned. If not populated, all available balances will be returned. */
  currencies: Option[List[Currency]]
)