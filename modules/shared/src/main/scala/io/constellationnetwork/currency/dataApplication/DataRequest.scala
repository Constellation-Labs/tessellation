package io.constellationnetwork.currency.dataApplication

import io.constellationnetwork.currency.dataApplication.DataTransaction._
import io.constellationnetwork.security.signature.Signed

sealed trait DataRequest
case class SingleDataUpdateRequest(dataUpdate: Signed[DataUpdate]) extends DataRequest
case class DataTransactionsRequest(transactions: DataTransactions) extends DataRequest
