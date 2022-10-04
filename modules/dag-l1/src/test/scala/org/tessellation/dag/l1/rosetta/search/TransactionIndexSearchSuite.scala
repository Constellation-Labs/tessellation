package org.tessellation.dag.l1.rosetta.search

import cats.effect.{IO, Resource}

import org.tessellation.dag.l1.config.types.DBConfig
import org.tessellation.dag.l1.domain.transaction.storage.TransactionIndexStorage
import org.tessellation.dag.l1.infrastructure.db.Database
import org.tessellation.dag.l1.infrastructure.transaction.storage.SignedTransactionGenerator.signedTransactionGen
import org.tessellation.dag.l1.infrastructure.transaction.storage.TransactionIndexDbStorage
import org.tessellation.dag.l1.rosetta.api.model.BlockSearchRequest
import org.tessellation.dag.l1.rosetta.model.network.NetworkStatus
import org.tessellation.ext.cats.effect._
import org.tessellation.ext.crypto.RefinedHashable
import org.tessellation.ext.kryo.KryoRegistrationId
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.height.Height
import org.tessellation.schema.transaction.{Transaction, TransactionReference}
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.SignatureProof

import ciris.Secret
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.{Interval, NonNegative}
import eu.timepit.refined.scalacheck.numeric._
import eu.timepit.refined.types.string.NonEmptyString
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalacheck.Gen.listOfN
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object TransactionIndexSearchSuite extends SimpleIOSuite with Checkers {
  val arbitraryNetworkStatus = Gen.oneOf(NetworkStatus.accepted, NetworkStatus.canceled, NetworkStatus.reverted)
  val arbitraryNonNegLong = arbitrary[Long Refined NonNegative]

  val generateData = for {
    height <- arbitraryNonNegLong.map(Height(_))
    networkStatus <- arbitraryNetworkStatus
    signedTransaction <- signedTransactionGen
  } yield (height, networkStatus, signedTransaction)

  val generatedData = listOfN(3, generateData)

  val kryoRegistrar: Map[Class[_], KryoRegistrationId[Interval.Closed[300, 399]]] = Map(
    classOf[Signed[Transaction]] -> 390,
    SignatureProof.OrderingInstance.getClass -> 391,
    classOf[SignatureProof] -> 392,
    classOf[Transaction] -> 393,
    classOf[Refined[NonEmptyString, String]] -> 394,
    classOf[TransactionReference] -> 395
  )
  expect(true)

  val kryoSerializer = KryoSerializer.forAsync[IO](kryoRegistrar)

  val dbConfig = DBConfig("org.sqlite.JDBC", "jdbc:sqlite::memory:", "sa", Secret(""))

  def testResource: Resource[IO, (TransactionIndexStorage[IO], KryoSerializer[IO])] =
    Database.forAsync[IO](dbConfig).flatMap { implicit db =>
      kryoSerializer.map(implicit kryo => (TransactionIndexDbStorage.make[IO], kryo))
    }

  test("Test writing transaction index values to the database - get transaction index values by height") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHeight = data.head._1
          val matches = data.map(datum => datum._1.value <= data.head._1.value).count(_ == true)

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
            implicit val kryoImplicit = kryo

            val request =
              BlockSearchRequest(false, false, None, None, None, None, None, Some(referenceHeight.value.value))
            val searchIo = Database
              .forAsync[IO](dbConfig)
              .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

            searchIo.use(
              search =>
                search
                  .searchTransactions(request)
                  .map(_.map(x => expect.all(x.length == matches, x.forall(_.height <= referenceHeight.value.value))))
                  .toOption
                  .getOrElse(IO.pure(expect(false)))
            )
          }
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by id") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          referenceHash
            .map(
              hash =>
                transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
                  implicit val kryoImplicit = kryo

                  val request = BlockSearchRequest(false, false, None, None, None, None, Some(hash.value), None)
                  val searchIo = Database
                    .forAsync[IO](dbConfig)
                    .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

                  searchIo.use(
                    search =>
                      search
                        .searchTransactions(request)
                        .map(
                          _.map(
                            x =>
                              expect.all(
                                x.length == 1,
                                x.forall(
                                  y =>
                                    new RefinedHashable(y.signedTransaction)(kryo).hash
                                      .flatMap(z => referenceHash.map(_ == z))
                                      .toOption
                                      .getOrElse(false)
                                )
                              )
                          )
                        )
                        .toOption
                        .getOrElse(IO.pure(expect(false)))
                  )
                }
            )
            .toOption
            .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by id, but failed height") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          referenceHash
            .map(
              hash =>
                transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
                  implicit val kryoImplicit = kryo

                  val request =
                    BlockSearchRequest(false, false, None, None, None, None, Some(hash.value), Some(Long.MinValue))
                  val searchIo = Database
                    .forAsync[IO](dbConfig)
                    .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

                  searchIo.use(
                    search =>
                      search
                        .searchTransactions(request)
                        .map(
                          _.map(
                            x =>
                              expect.all(
                                x.length == 0
                              )
                          )
                        )
                        .toOption
                        .getOrElse(IO.pure(expect(false)))
                  )
                }
            )
            .toOption
            .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by address") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceAddress = data.head._3.source
          val matches = data
            .map(datum => datum._3.source == referenceAddress || datum._3.destination == referenceAddress)
            .count(_ == true)

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
            implicit val kryoImplicit = kryo

            val request =
              BlockSearchRequest(false, false, Some(referenceAddress.value.value), None, None, None, None, None)
            val searchIo = Database
              .forAsync[IO](dbConfig)
              .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

            searchIo.use(
              search =>
                search
                  .searchTransactions(request)
                  .map(
                    _.map(
                      x =>
                        expect.all(
                          x.length == matches,
                          x.forall(
                            y => y.signedTransaction.source == referenceAddress || y.signedTransaction.destination == referenceAddress
                          )
                        )
                    )
                  )
                  .toOption
                  .getOrElse(IO.pure(expect(false)))
            )
          }
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by network status") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceNetworkStatus = data.head._2
          val matches = data.map(datum => datum._2 == referenceNetworkStatus).count(_ == true)

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
            implicit val kryoImplicit = kryo

            val request =
              BlockSearchRequest(false, false, None, Some(referenceNetworkStatus.value.value), None, None, None, None)
            val searchIo = Database
              .forAsync[IO](dbConfig)
              .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

            searchIo.use(
              search =>
                search
                  .searchTransactions(request)
                  .map(_.map(x => expect.all(x.length == matches, x.forall(_.networkStatus == referenceNetworkStatus))))
                  .toOption
                  .getOrElse(IO.pure(expect(false)))
            )
          }
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by network status, limited"
  ) {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, NetworkStatus.accepted, bytes))
                  )
                  .toOption
            )
            .toMap

          transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
            implicit val kryoImplicit = kryo

            val request = BlockSearchRequest(
              false,
              false,
              None,
              Some(NetworkStatus.accepted.value.value),
              Some(2),
              None,
              None,
              None
            )
            val searchIo = Database
              .forAsync[IO](dbConfig)
              .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

            searchIo.use(
              search =>
                search
                  .searchTransactions(request)
                  .map(_.map(x => expect.all(x.length == 2, x.forall(_.networkStatus == NetworkStatus.accepted))))
                  .toOption
                  .getOrElse(IO.pure(expect(false)))
            )
          }
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by network status, offset") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, NetworkStatus.accepted, bytes))
                  )
                  .toOption
            )
            .toMap

          transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
            implicit val kryoImplicit = kryo

            val request =
              BlockSearchRequest(
                false,
                false,
                None,
                Some(NetworkStatus.accepted.value.value),
                Some(Int.MaxValue),
                Some(1),
                None,
                None
              )
            val searchIo = Database
              .forAsync[IO](dbConfig)
              .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

            searchIo.use(
              search =>
                search
                  .searchTransactions(request)
                  .map(_.map(x => expect.all(x.length == 2, x.forall(_.networkStatus == NetworkStatus.accepted))))
                  .toOption
                  .getOrElse(IO.pure(expect(false)))
            )
          }
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by network status, offset, error"
  ) {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, NetworkStatus.accepted, bytes))
                  )
                  .toOption
            )
            .toMap

          transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
            implicit val kryoImplicit = kryo

            val request = BlockSearchRequest(
              false,
              false,
              None,
              Some(NetworkStatus.accepted.value.value),
              None,
              Some(1),
              None,
              None
            )
            val searchIo = Database
              .forAsync[IO](dbConfig)
              .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

            searchIo.use { search =>
              val result = search
                .searchTransactions(request)
              IO.pure(expect(result.isLeft))
            }
          }
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by id and address") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash
          val referenceAddress = data.head._3.source

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          referenceHash
            .map(
              hash =>
                transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
                  implicit val kryoImplicit = kryo

                  val request = BlockSearchRequest(
                    false,
                    false,
                    Some(referenceAddress.value.value),
                    None,
                    None,
                    None,
                    Some(hash.value),
                    None
                  )
                  val searchIo = Database
                    .forAsync[IO](dbConfig)
                    .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

                  searchIo.use(
                    search =>
                      search
                        .searchTransactions(request)
                        .map(
                          _.map(
                            x =>
                              expect.all(
                                x.length == 1,
                                x.forall(
                                  y =>
                                    new RefinedHashable(y.signedTransaction)(kryo).hash
                                      .flatMap(z => referenceHash.map(_ == z))
                                      .toOption
                                      .getOrElse(false) && y.signedTransaction.source == referenceAddress
                                )
                              )
                          )
                        )
                        .toOption
                        .getOrElse(IO.pure(expect(false)))
                  )
                }
            )
            .toOption
            .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by id and network status") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash
          val referenceNetworkStatus = data.head._2

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          referenceHash
            .map(
              hash =>
                transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
                  implicit val kryoImplicit = kryo

                  val request =
                    BlockSearchRequest(
                      false,
                      false,
                      None,
                      Some(referenceNetworkStatus.value.value),
                      None,
                      None,
                      Some(hash.value),
                      None
                    )
                  val searchIo = Database
                    .forAsync[IO](dbConfig)
                    .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

                  searchIo.use(
                    search =>
                      search
                        .searchTransactions(request)
                        .map(
                          _.map(
                            x =>
                              expect.all(
                                x.length == 1,
                                x.forall(
                                  y =>
                                    new RefinedHashable(y.signedTransaction)(kryo).hash
                                      .flatMap(z => referenceHash.map(_ == z))
                                      .toOption
                                      .getOrElse(false) && y.networkStatus == referenceNetworkStatus
                                )
                              )
                          )
                        )
                        .toOption
                        .getOrElse(IO.pure(expect(false)))
                  )
                }
            )
            .toOption
            .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by address and network status"
  ) {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceAddress = data.head._3.source
          val referenceNetworkStatus = data.head._2
          val matches = data
            .map(
              datum => (datum._3.source == referenceAddress || datum._3.destination == referenceAddress) && datum._2 == referenceNetworkStatus
            )
            .count(_ == true)

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
            implicit val kryoImplicit = kryo

            val request = BlockSearchRequest(
              false,
              false,
              Some(referenceAddress.value.value),
              Some(referenceNetworkStatus.value.value),
              None,
              None,
              None,
              None
            )
            val searchIo = Database
              .forAsync[IO](dbConfig)
              .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

            searchIo.use(
              search =>
                search
                  .searchTransactions(request)
                  .map(
                    _.map(
                      x =>
                        expect.all(
                          x.length == matches,
                          x.forall(
                            y =>
                              (y.signedTransaction.source == referenceAddress || y.signedTransaction.destination == referenceAddress) && y.networkStatus == referenceNetworkStatus
                          )
                        )
                    )
                  )
                  .toOption
                  .getOrElse(IO.pure(expect(false)))
            )
          }
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by id, address, and network status"
  ) {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash
          val referenceAddress = data.head._3.source
          val referenceNetworkStatus = data.head._2

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          referenceHash
            .map(
              hash =>
                transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
                  implicit val kryoImplicit = kryo

                  val request =
                    BlockSearchRequest(
                      false,
                      false,
                      Some(referenceAddress.value.value),
                      Some(referenceNetworkStatus.value.value),
                      None,
                      None,
                      Some(hash.value),
                      None
                    )
                  val searchIo = Database
                    .forAsync[IO](dbConfig)
                    .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

                  searchIo.use(
                    search =>
                      search
                        .searchTransactions(request)
                        .map(
                          _.map(
                            x =>
                              expect.all(
                                x.length == 1,
                                x.forall(
                                  y =>
                                    new RefinedHashable(y.signedTransaction)(kryo).hash
                                      .flatMap(z => referenceHash.map(_ == z))
                                      .toOption
                                      .getOrElse(
                                        false
                                      ) && y.networkStatus == referenceNetworkStatus && y.signedTransaction.source == referenceAddress
                                )
                              )
                          )
                        )
                        .toOption
                        .getOrElse(IO.pure(expect(false)))
                  )
                }
            )
            .toOption
            .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by height, explicit and") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHeight = data.head._1
          val matches = data.map(datum => datum._1.value <= data.head._1.value).count(_ == true)

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
            implicit val kryoImplicit = kryo

            val request =
              BlockSearchRequest(false, true, None, None, None, None, None, Some(referenceHeight.value.value))
            val searchIo = Database
              .forAsync[IO](dbConfig)
              .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

            searchIo.use(
              search =>
                search
                  .searchTransactions(request)
                  .map(_.map(x => expect.all(x.length == matches, x.forall(_.height <= referenceHeight.value.value))))
                  .toOption
                  .getOrElse(IO.pure(expect(false)))
            )
          }
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by id, explicit and") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          referenceHash
            .map(
              hash =>
                transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
                  implicit val kryoImplicit = kryo

                  val request = BlockSearchRequest(false, true, None, None, None, None, Some(hash.value), None)
                  val searchIo = Database
                    .forAsync[IO](dbConfig)
                    .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

                  searchIo.use(
                    search =>
                      search
                        .searchTransactions(request)
                        .map(
                          _.map(
                            x =>
                              expect.all(
                                x.length == 1,
                                x.forall(
                                  y =>
                                    new RefinedHashable(y.signedTransaction)(kryo).hash
                                      .flatMap(z => referenceHash.map(_ == z))
                                      .toOption
                                      .getOrElse(false)
                                )
                              )
                          )
                        )
                        .toOption
                        .getOrElse(IO.pure(expect(false)))
                  )
                }
            )
            .toOption
            .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by id, but failed height, explicit and"
  ) {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          referenceHash
            .map(
              hash =>
                transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
                  implicit val kryoImplicit = kryo

                  val request =
                    BlockSearchRequest(false, true, None, None, None, None, Some(hash.value), Some(Long.MinValue))
                  val searchIo = Database
                    .forAsync[IO](dbConfig)
                    .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

                  searchIo.use(
                    search =>
                      search
                        .searchTransactions(request)
                        .map(
                          _.map(
                            x =>
                              expect.all(
                                x.length == 0
                              )
                          )
                        )
                        .toOption
                        .getOrElse(IO.pure(expect(false)))
                  )
                }
            )
            .toOption
            .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by address, explicit and") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceAddress = data.head._3.source
          val matches = data
            .map(datum => datum._3.source == referenceAddress || datum._3.destination == referenceAddress)
            .count(_ == true)

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
            implicit val kryoImplicit = kryo

            val request =
              BlockSearchRequest(false, true, Some(referenceAddress.value.value), None, None, None, None, None)
            val searchIo = Database
              .forAsync[IO](dbConfig)
              .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

            searchIo.use(
              search =>
                search
                  .searchTransactions(request)
                  .map(
                    _.map(
                      x =>
                        expect.all(
                          x.length == matches,
                          x.forall(
                            y => y.signedTransaction.source == referenceAddress || y.signedTransaction.destination == referenceAddress
                          )
                        )
                    )
                  )
                  .toOption
                  .getOrElse(IO.pure(expect(false)))
            )
          }
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by network status, explicit and"
  ) {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceNetworkStatus = data.head._2
          val matches = data.map(datum => datum._2 == referenceNetworkStatus).count(_ == true)

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
            implicit val kryoImplicit = kryo

            val request =
              BlockSearchRequest(false, true, None, Some(referenceNetworkStatus.value.value), None, None, None, None)
            val searchIo = Database
              .forAsync[IO](dbConfig)
              .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

            searchIo.use(
              search =>
                search
                  .searchTransactions(request)
                  .map(_.map(x => expect.all(x.length == matches, x.forall(_.networkStatus == referenceNetworkStatus))))
                  .toOption
                  .getOrElse(IO.pure(expect(false)))
            )
          }
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by network status, limited, explicit and"
  ) {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, NetworkStatus.accepted, bytes))
                  )
                  .toOption
            )
            .toMap

          transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
            implicit val kryoImplicit = kryo

            val request =
              BlockSearchRequest(false, true, None, Some(NetworkStatus.accepted.value.value), Some(2), None, None, None)
            val searchIo = Database
              .forAsync[IO](dbConfig)
              .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

            searchIo.use(
              search =>
                search
                  .searchTransactions(request)
                  .map(_.map(x => expect.all(x.length == 2, x.forall(_.networkStatus == NetworkStatus.accepted))))
                  .toOption
                  .getOrElse(IO.pure(expect(false)))
            )
          }
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by network status, offset, explicit and"
  ) {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, NetworkStatus.accepted, bytes))
                  )
                  .toOption
            )
            .toMap

          transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
            implicit val kryoImplicit = kryo

            val request =
              BlockSearchRequest(
                false,
                true,
                None,
                Some(NetworkStatus.accepted.value.value),
                Some(Int.MaxValue),
                Some(1),
                None,
                None
              )
            val searchIo = Database
              .forAsync[IO](dbConfig)
              .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

            searchIo.use(
              search =>
                search
                  .searchTransactions(request)
                  .map(_.map(x => expect.all(x.length == 2, x.forall(_.networkStatus == NetworkStatus.accepted))))
                  .toOption
                  .getOrElse(IO.pure(expect(false)))
            )
          }
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by network status, offset, error, explicit and"
  ) {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, NetworkStatus.accepted, bytes))
                  )
                  .toOption
            )
            .toMap

          transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
            implicit val kryoImplicit = kryo

            val request =
              BlockSearchRequest(false, true, None, Some(NetworkStatus.accepted.value.value), None, Some(1), None, None)
            val searchIo = Database
              .forAsync[IO](dbConfig)
              .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

            searchIo.use { search =>
              val result = search
                .searchTransactions(request)
              IO.pure(expect(result.isLeft))
            }
          }
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by id and address") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash
          val referenceAddress = data.head._3.source

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          referenceHash
            .map(
              hash =>
                transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
                  implicit val kryoImplicit = kryo

                  val request = BlockSearchRequest(
                    false,
                    true,
                    Some(referenceAddress.value.value),
                    None,
                    None,
                    None,
                    Some(hash.value),
                    None
                  )
                  val searchIo = Database
                    .forAsync[IO](dbConfig)
                    .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

                  searchIo.use(
                    search =>
                      search
                        .searchTransactions(request)
                        .map(
                          _.map(
                            x =>
                              expect.all(
                                x.length == 1,
                                x.forall(
                                  y =>
                                    new RefinedHashable(y.signedTransaction)(kryo).hash
                                      .flatMap(z => referenceHash.map(_ == z))
                                      .toOption
                                      .getOrElse(false) && y.signedTransaction.source == referenceAddress
                                )
                              )
                          )
                        )
                        .toOption
                        .getOrElse(IO.pure(expect(false)))
                  )
                }
            )
            .toOption
            .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by id and network status") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash
          val referenceNetworkStatus = data.head._2

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          referenceHash
            .map(
              hash =>
                transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
                  implicit val kryoImplicit = kryo

                  val request =
                    BlockSearchRequest(
                      false,
                      true,
                      None,
                      Some(referenceNetworkStatus.value.value),
                      None,
                      None,
                      Some(hash.value),
                      None
                    )
                  val searchIo = Database
                    .forAsync[IO](dbConfig)
                    .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

                  searchIo.use(
                    search =>
                      search
                        .searchTransactions(request)
                        .map(
                          _.map(
                            x =>
                              expect.all(
                                x.length == 1,
                                x.forall(
                                  y =>
                                    new RefinedHashable(y.signedTransaction)(kryo).hash
                                      .flatMap(z => referenceHash.map(_ == z))
                                      .toOption
                                      .getOrElse(false) && y.networkStatus == referenceNetworkStatus
                                )
                              )
                          )
                        )
                        .toOption
                        .getOrElse(IO.pure(expect(false)))
                  )
                }
            )
            .toOption
            .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by address and network status"
  ) {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceAddress = data.head._3.source
          val referenceNetworkStatus = data.head._2
          val matches = data
            .map(
              datum => (datum._3.source == referenceAddress || datum._3.destination == referenceAddress) && datum._2 == referenceNetworkStatus
            )
            .count(_ == true)

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
            implicit val kryoImplicit = kryo

            val request = BlockSearchRequest(
              false,
              true,
              Some(referenceAddress.value.value),
              Some(referenceNetworkStatus.value.value),
              None,
              None,
              None,
              None
            )
            val searchIo = Database
              .forAsync[IO](dbConfig)
              .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

            searchIo.use(
              search =>
                search
                  .searchTransactions(request)
                  .map(
                    _.map(
                      x =>
                        expect.all(
                          x.length == matches,
                          x.forall(
                            y =>
                              (y.signedTransaction.source == referenceAddress || y.signedTransaction.destination == referenceAddress) && y.networkStatus == referenceNetworkStatus
                          )
                        )
                    )
                  )
                  .toOption
                  .getOrElse(IO.pure(expect(false)))
            )
          }
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by id, address, and network status"
  ) {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash
          val referenceAddress = data.head._3.source
          val referenceNetworkStatus = data.head._2

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          referenceHash
            .map(
              hash =>
                transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
                  implicit val kryoImplicit = kryo

                  val request =
                    BlockSearchRequest(
                      false,
                      true,
                      Some(referenceAddress.value.value),
                      Some(referenceNetworkStatus.value.value),
                      None,
                      None,
                      Some(hash.value),
                      None
                    )
                  val searchIo = Database
                    .forAsync[IO](dbConfig)
                    .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

                  searchIo.use(
                    search =>
                      search
                        .searchTransactions(request)
                        .map(
                          _.map(
                            x =>
                              expect.all(
                                x.length == 1,
                                x.forall(
                                  y =>
                                    new RefinedHashable(y.signedTransaction)(kryo).hash
                                      .flatMap(z => referenceHash.map(_ == z))
                                      .toOption
                                      .getOrElse(
                                        false
                                      ) && y.networkStatus == referenceNetworkStatus && y.signedTransaction.source == referenceAddress
                                )
                              )
                          )
                        )
                        .toOption
                        .getOrElse(IO.pure(expect(false)))
                  )
                }
            )
            .toOption
            .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by id, address, and height"
  ) {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash
          val referenceAddress = data.head._3.source
          val referenceHeight = data.head._1

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          referenceHash
            .map(
              hash =>
                transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
                  implicit val kryoImplicit = kryo

                  val request =
                    BlockSearchRequest(
                      false,
                      true,
                      Some(referenceAddress.value.value),
                      None,
                      None,
                      None,
                      Some(hash.value),
                      Some(referenceHeight.value.value)
                    )
                  val searchIo = Database
                    .forAsync[IO](dbConfig)
                    .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

                  searchIo.use(
                    search =>
                      search
                        .searchTransactions(request)
                        .map(
                          _.map(
                            x =>
                              expect.all(
                                x.length == 1,
                                x.forall(
                                  y =>
                                    new RefinedHashable(y.signedTransaction)(kryo).hash
                                      .flatMap(z => referenceHash.map(_ == z))
                                      .toOption
                                      .getOrElse(
                                        false
                                      ) && y.signedTransaction.source == referenceAddress && y.height <= referenceHeight.value.value
                                )
                              )
                          )
                        )
                        .toOption
                        .getOrElse(IO.pure(expect(false)))
                  )
                }
            )
            .toOption
            .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by id, network status, and height"
  ) {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash
          val referenceNetworkStatus = data.head._2
          val referenceHeight = data.head._1

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          referenceHash
            .map(
              hash =>
                transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
                  implicit val kryoImplicit = kryo

                  val request =
                    BlockSearchRequest(
                      false,
                      true,
                      None,
                      Some(referenceNetworkStatus.value.value),
                      None,
                      None,
                      Some(hash.value),
                      Some(referenceHeight.value.value)
                    )
                  val searchIo = Database
                    .forAsync[IO](dbConfig)
                    .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

                  searchIo.use(
                    search =>
                      search
                        .searchTransactions(request)
                        .map(
                          _.map(
                            x =>
                              expect.all(
                                x.length == 1,
                                x.forall(
                                  y =>
                                    new RefinedHashable(y.signedTransaction)(kryo).hash
                                      .flatMap(z => referenceHash.map(_ == z))
                                      .toOption
                                      .getOrElse(
                                        false
                                      ) && y.networkStatus == referenceNetworkStatus && y.height <= referenceHeight.value.value
                                )
                              )
                          )
                        )
                        .toOption
                        .getOrElse(IO.pure(expect(false)))
                  )
                }
            )
            .toOption
            .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by id, address, network status, and height"
  ) {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash
          val referenceAddress = data.head._3.source
          val referenceNetworkStatus = data.head._2
          val referenceHeight = data.head._1

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          referenceHash
            .map(
              hash =>
                transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
                  implicit val kryoImplicit = kryo

                  val request =
                    BlockSearchRequest(
                      false,
                      true,
                      Some(referenceAddress.value.value),
                      Some(referenceNetworkStatus.value.value),
                      None,
                      None,
                      Some(hash.value),
                      Some(referenceHeight.value.value)
                    )
                  val searchIo = Database
                    .forAsync[IO](dbConfig)
                    .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

                  searchIo.use(
                    search =>
                      search
                        .searchTransactions(request)
                        .map(
                          _.map(
                            x =>
                              expect.all(
                                x.length == 1,
                                x.forall(
                                  y =>
                                    new RefinedHashable(y.signedTransaction)(kryo).hash
                                      .flatMap(z => referenceHash.map(_ == z))
                                      .toOption
                                      .getOrElse(
                                        false
                                      ) && y.networkStatus == referenceNetworkStatus && y.signedTransaction.source == referenceAddress && y.height <= referenceHeight.value.value
                                )
                              )
                          )
                        )
                        .toOption
                        .getOrElse(IO.pure(expect(false)))
                  )
                }
            )
            .toOption
            .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  //

  test("Test writing transaction index values to the database - get transaction index values by height, explicit or") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHeight = data.head._1
          val matches = data.map(datum => datum._1.value <= data.head._1.value).count(_ == true)

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
            implicit val kryoImplicit = kryo

            val request =
              BlockSearchRequest(true, false, None, None, None, None, None, Some(referenceHeight.value.value))
            val searchIo = Database
              .forAsync[IO](dbConfig)
              .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

            searchIo.use(
              search =>
                search
                  .searchTransactions(request)
                  .map(_.map(x => expect.all(x.length == matches, x.forall(_.height <= referenceHeight.value.value))))
                  .toOption
                  .getOrElse(IO.pure(expect(false)))
            )
          }
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by id, explicit or") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          referenceHash
            .map(
              hash =>
                transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
                  implicit val kryoImplicit = kryo

                  val request = BlockSearchRequest(true, false, None, None, None, None, Some(hash.value), None)
                  val searchIo = Database
                    .forAsync[IO](dbConfig)
                    .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

                  searchIo.use(
                    search =>
                      search
                        .searchTransactions(request)
                        .map(
                          _.map(
                            x =>
                              expect.all(
                                x.length == 1,
                                x.forall(
                                  y =>
                                    new RefinedHashable(y.signedTransaction)(kryo).hash
                                      .flatMap(z => referenceHash.map(_ == z))
                                      .toOption
                                      .getOrElse(false)
                                )
                              )
                          )
                        )
                        .toOption
                        .getOrElse(IO.pure(expect(false)))
                  )
                }
            )
            .toOption
            .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by address, explicit or") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceAddress = data.head._3.source
          val matches = data
            .map(datum => datum._3.source == referenceAddress || datum._3.destination == referenceAddress)
            .count(_ == true)

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
            implicit val kryoImplicit = kryo

            val request =
              BlockSearchRequest(true, false, Some(referenceAddress.value.value), None, None, None, None, None)
            val searchIo = Database
              .forAsync[IO](dbConfig)
              .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

            searchIo.use(
              search =>
                search
                  .searchTransactions(request)
                  .map(
                    _.map(
                      x =>
                        expect.all(
                          x.length == matches,
                          x.forall(
                            y => y.signedTransaction.source == referenceAddress || y.signedTransaction.destination == referenceAddress
                          )
                        )
                    )
                  )
                  .toOption
                  .getOrElse(IO.pure(expect(false)))
            )
          }
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by network status, explicit or"
  ) {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceNetworkStatus = data.head._2
          val matches = data.map(datum => datum._2 == referenceNetworkStatus).count(_ == true)

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
            implicit val kryoImplicit = kryo

            val request =
              BlockSearchRequest(true, false, None, Some(referenceNetworkStatus.value.value), None, None, None, None)
            val searchIo = Database
              .forAsync[IO](dbConfig)
              .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

            searchIo.use(
              search =>
                search
                  .searchTransactions(request)
                  .map(_.map(x => expect.all(x.length == matches, x.forall(_.networkStatus == referenceNetworkStatus))))
                  .toOption
                  .getOrElse(IO.pure(expect(false)))
            )
          }
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by network status, limited, explicit or"
  ) {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, NetworkStatus.accepted, bytes))
                  )
                  .toOption
            )
            .toMap

          transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
            implicit val kryoImplicit = kryo

            val request =
              BlockSearchRequest(true, false, None, Some(NetworkStatus.accepted.value.value), Some(2), None, None, None)
            val searchIo = Database
              .forAsync[IO](dbConfig)
              .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

            searchIo.use(
              search =>
                search
                  .searchTransactions(request)
                  .map(_.map(x => expect.all(x.length == 2, x.forall(_.networkStatus == NetworkStatus.accepted))))
                  .toOption
                  .getOrElse(IO.pure(expect(false)))
            )
          }
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by network status, offset, explicit or"
  ) {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, NetworkStatus.accepted, bytes))
                  )
                  .toOption
            )
            .toMap

          transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
            implicit val kryoImplicit = kryo

            val request =
              BlockSearchRequest(
                true,
                false,
                None,
                Some(NetworkStatus.accepted.value.value),
                Some(Int.MaxValue),
                Some(1),
                None,
                None
              )
            val searchIo = Database
              .forAsync[IO](dbConfig)
              .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

            searchIo.use(
              search =>
                search
                  .searchTransactions(request)
                  .map(_.map(x => expect.all(x.length == 2, x.forall(_.networkStatus == NetworkStatus.accepted))))
                  .toOption
                  .getOrElse(IO.pure(expect(false)))
            )
          }
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by network status, offset, error, explicit or"
  ) {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, NetworkStatus.accepted, bytes))
                  )
                  .toOption
            )
            .toMap

          transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
            implicit val kryoImplicit = kryo

            val request =
              BlockSearchRequest(true, false, None, Some(NetworkStatus.accepted.value.value), None, Some(1), None, None)
            val searchIo = Database
              .forAsync[IO](dbConfig)
              .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

            searchIo.use { search =>
              val result = search
                .searchTransactions(request)
              IO.pure(expect(result.isLeft))
            }
          }
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by id or height") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash
          val referenceHeight = data.head._1
          val matches = data.map(_._1.value <= referenceHeight.value.value).count(_ == true)

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          referenceHash
            .map(
              hash =>
                transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
                  implicit val kryoImplicit = kryo

                  val request = BlockSearchRequest(
                    true,
                    false,
                    None,
                    None,
                    None,
                    None,
                    Some(hash.value),
                    Some(referenceHeight.value.value)
                  )
                  val searchIo = Database
                    .forAsync[IO](dbConfig)
                    .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

                  searchIo.use(
                    search =>
                      search
                        .searchTransactions(request)
                        .map(
                          _.map(
                            x =>
                              expect.all(
                                x.length == matches,
                                x.forall(
                                  y =>
                                    referenceHash
                                      .flatMap(z => new RefinedHashable(y.signedTransaction)(kryo).hash.map(_ == z))
                                      .toOption
                                      .getOrElse(false) || y.height <= referenceHeight.value.value
                                )
                              )
                          )
                        )
                        .toOption
                        .getOrElse(IO.pure(expect(false)))
                  )
                }
            )
            .toOption
            .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by id or address") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash
          val referenceAddress = data.head._3.source
          val matches = data
            .map(datum => datum._3.source == referenceAddress || datum._3.destination == referenceAddress)
            .count(_ == true)

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          referenceHash
            .map(
              hash =>
                transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
                  implicit val kryoImplicit = kryo

                  val request = BlockSearchRequest(
                    true,
                    false,
                    Some(referenceAddress.value.value),
                    None,
                    None,
                    None,
                    Some(hash.value),
                    None
                  )
                  val searchIo = Database
                    .forAsync[IO](dbConfig)
                    .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

                  searchIo.use(
                    search =>
                      search
                        .searchTransactions(request)
                        .map(
                          _.map(
                            x =>
                              expect.all(
                                x.length == matches,
                                x.forall(
                                  y =>
                                    referenceHash
                                      .flatMap(z => new RefinedHashable(y.signedTransaction)(kryo).hash.map(_ == z))
                                      .toOption
                                      .getOrElse(
                                        false
                                      ) || y.signedTransaction.source == referenceAddress || y.signedTransaction.destination == referenceAddress
                                )
                              )
                          )
                        )
                        .toOption
                        .getOrElse(IO.pure(expect(false)))
                  )
                }
            )
            .toOption
            .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by id or network status") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash
          val referenceNetworkStatus = data.head._2
          val matches = data.map(datum => datum._2 == referenceNetworkStatus).count(_ == true)

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          referenceHash
            .map(
              hash =>
                transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
                  implicit val kryoImplicit = kryo

                  val request =
                    BlockSearchRequest(
                      true,
                      false,
                      None,
                      Some(referenceNetworkStatus.value.value),
                      None,
                      None,
                      Some(hash.value),
                      None
                    )
                  val searchIo = Database
                    .forAsync[IO](dbConfig)
                    .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

                  searchIo.use(
                    search =>
                      search
                        .searchTransactions(request)
                        .map(
                          _.map(
                            x =>
                              expect.all(
                                x.length == matches,
                                x.forall(
                                  y =>
                                    referenceHash
                                      .flatMap(z => new RefinedHashable(y.signedTransaction)(kryo).hash.map(_ == z))
                                      .toOption
                                      .getOrElse(false) || y.networkStatus == referenceNetworkStatus
                                )
                              )
                          )
                        )
                        .toOption
                        .getOrElse(IO.pure(expect(false)))
                  )
                }
            )
            .toOption
            .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by address or network status"
  ) {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceAddress = data.head._3.source
          val referenceNetworkStatus = data.head._2
          val matches = data
            .map(
              datum => datum._3.source == referenceAddress || datum._3.destination == referenceAddress || datum._2 == referenceNetworkStatus
            )
            .count(_ == true)

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
            implicit val kryoImplicit = kryo

            val request = BlockSearchRequest(
              true,
              false,
              Some(referenceAddress.value.value),
              Some(referenceNetworkStatus.value.value),
              None,
              None,
              None,
              None
            )
            val searchIo = Database
              .forAsync[IO](dbConfig)
              .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

            searchIo.use(
              search =>
                search
                  .searchTransactions(request)
                  .map(
                    _.map(
                      x =>
                        expect.all(
                          x.length == matches,
                          x.forall(
                            y =>
                              y.signedTransaction.source == referenceAddress || y.signedTransaction.destination == referenceAddress || y.networkStatus == referenceNetworkStatus
                          )
                        )
                    )
                  )
                  .toOption
                  .getOrElse(IO.pure(expect(false)))
            )
          }
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by address or height") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceAddress = data.head._3.source
          val referenceHeight = data.head._1
          val matches = data
            .map(
              datum =>
                datum._3.source == referenceAddress || datum._3.destination == referenceAddress || datum._1.value <= referenceHeight.value
            )
            .count(_ == true)

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
            implicit val kryoImplicit = kryo

            val request = BlockSearchRequest(
              true,
              false,
              Some(referenceAddress.value.value),
              None,
              None,
              None,
              None,
              Some(referenceHeight.value.value)
            )
            val searchIo = Database
              .forAsync[IO](dbConfig)
              .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

            searchIo.use(
              search =>
                search
                  .searchTransactions(request)
                  .map(
                    _.map(
                      x =>
                        expect.all(
                          x.length == matches,
                          x.forall(
                            y =>
                              y.signedTransaction.source == referenceAddress || y.signedTransaction.destination == referenceAddress || y.height <= referenceHeight.value.value
                          )
                        )
                    )
                  )
                  .toOption
                  .getOrElse(IO.pure(expect(false)))
            )
          }
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by network status or height"
  ) {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceNetworkStatus = data.head._2
          val referenceHeight = data.head._1
          val matches = data
            .map(datum => datum._2 == referenceNetworkStatus || datum._1.value <= referenceHeight.value)
            .count(_ == true)

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
            implicit val kryoImplicit = kryo

            val request = BlockSearchRequest(
              true,
              false,
              None,
              Some(referenceNetworkStatus.value.value),
              None,
              None,
              None,
              Some(referenceHeight.value.value)
            )
            val searchIo = Database
              .forAsync[IO](dbConfig)
              .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

            searchIo.use(
              search =>
                search
                  .searchTransactions(request)
                  .map(
                    _.map(
                      x =>
                        expect.all(
                          x.length == matches,
                          x.forall(
                            y => y.networkStatus == referenceNetworkStatus || y.height <= referenceHeight.value.value
                          )
                        )
                    )
                  )
                  .toOption
                  .getOrElse(IO.pure(expect(false)))
            )
          }
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by id, address, or network status"
  ) {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash
          val referenceAddress = data.head._3.source
          val referenceNetworkStatus = data.head._2
          val matches = data
            .map(
              datum => datum._3.source == referenceAddress || datum._3.destination == referenceAddress || datum._2 == referenceNetworkStatus
            )
            .count(_ == true)

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          referenceHash
            .map(
              hash =>
                transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
                  implicit val kryoImplicit = kryo

                  val request = BlockSearchRequest(
                    true,
                    false,
                    Some(referenceAddress.value.value),
                    Some(referenceNetworkStatus.value.value),
                    None,
                    None,
                    Some(hash.value),
                    None
                  )
                  val searchIo = Database
                    .forAsync[IO](dbConfig)
                    .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

                  searchIo.use(
                    search =>
                      search
                        .searchTransactions(request)
                        .map(
                          _.map(
                            x =>
                              expect.all(
                                x.length == matches,
                                x.forall(
                                  y =>
                                    referenceHash
                                      .flatMap(z => new RefinedHashable(y.signedTransaction)(kryo).hash.map(_ == z))
                                      .toOption
                                      .getOrElse(
                                        false
                                      ) || y.signedTransaction.source == referenceAddress || y.signedTransaction.destination == referenceAddress || y.networkStatus == referenceNetworkStatus
                                )
                              )
                          )
                        )
                        .toOption
                        .getOrElse(IO.pure(expect(false)))
                  )
                }
            )
            .toOption
            .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by id, address, or height") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash
          val referenceAddress = data.head._3.source
          val referenceHeight = data.head._1
          val matches = data
            .map(
              datum =>
                datum._3.source == referenceAddress || datum._3.destination == referenceAddress || datum._1.value <= referenceHeight.value
            )
            .count(_ == true)

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          referenceHash
            .map(
              hash =>
                transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
                  implicit val kryoImplicit = kryo

                  val request = BlockSearchRequest(
                    true,
                    false,
                    Some(referenceAddress.value.value),
                    None,
                    None,
                    None,
                    Some(hash.value),
                    Some(referenceHeight.value.value)
                  )
                  val searchIo = Database
                    .forAsync[IO](dbConfig)
                    .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

                  searchIo.use(
                    search =>
                      search
                        .searchTransactions(request)
                        .map(
                          _.map(
                            x =>
                              expect.all(
                                x.length == matches,
                                x.forall(
                                  y =>
                                    referenceHash
                                      .flatMap(z => new RefinedHashable(y.signedTransaction)(kryo).hash.map(_ == z))
                                      .toOption
                                      .getOrElse(
                                        false
                                      ) || y.signedTransaction.source == referenceAddress || y.signedTransaction.destination == referenceAddress || y.height <= referenceHeight.value.value
                                )
                              )
                          )
                        )
                        .toOption
                        .getOrElse(IO.pure(expect(false)))
                  )
                }
            )
            .toOption
            .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by id, network status, or height"
  ) {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash
          val referenceNetworkStatus = data.head._2
          val referenceHeight = data.head._1
          val matches = data
            .map(datum => datum._2 == referenceNetworkStatus || datum._1.value <= referenceHeight.value)
            .count(_ == true)

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          referenceHash
            .map(
              hash =>
                transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
                  implicit val kryoImplicit = kryo

                  val request = BlockSearchRequest(
                    true,
                    false,
                    None,
                    Some(referenceNetworkStatus.value.value),
                    None,
                    None,
                    Some(hash.value),
                    Some(referenceHeight.value.value)
                  )
                  val searchIo = Database
                    .forAsync[IO](dbConfig)
                    .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

                  searchIo.use(
                    search =>
                      search
                        .searchTransactions(request)
                        .map(
                          _.map(
                            x =>
                              expect.all(
                                x.length == matches,
                                x.forall(
                                  y =>
                                    referenceHash
                                      .flatMap(z => new RefinedHashable(y.signedTransaction)(kryo).hash.map(_ == z))
                                      .toOption
                                      .getOrElse(
                                        false
                                      ) || y.networkStatus == referenceNetworkStatus || y.height <= referenceHeight.value.value
                                )
                              )
                          )
                        )
                        .toOption
                        .getOrElse(IO.pure(expect(false)))
                  )
                }
            )
            .toOption
            .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by address, network status, or height"
  ) {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceAddress = data.head._3.source
          val referenceNetworkStatus = data.head._2
          val referenceHeight = data.head._1
          val matches = data
            .map(
              datum =>
                datum._3.source == referenceAddress || datum._3.destination == referenceAddress || datum._2 == referenceNetworkStatus || datum._1.value <= referenceHeight.value
            )
            .count(_ == true)

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
            implicit val kryoImplicit = kryo

            val request = BlockSearchRequest(
              true,
              false,
              Some(referenceAddress.value.value),
              Some(referenceNetworkStatus.value.value),
              None,
              None,
              None,
              Some(referenceHeight.value.value)
            )
            val searchIo = Database
              .forAsync[IO](dbConfig)
              .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

            searchIo.use(
              search =>
                search
                  .searchTransactions(request)
                  .map(
                    _.map(
                      x =>
                        expect.all(
                          x.length == matches,
                          x.forall(
                            y =>
                              y.signedTransaction.source == referenceAddress || y.signedTransaction.destination == referenceAddress || y.networkStatus == referenceNetworkStatus || y.height <= referenceHeight.value.value
                          )
                        )
                    )
                  )
                  .toOption
                  .getOrElse(IO.pure(expect(false)))
            )
          }
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by id, address, network status, or height"
  ) {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash
          val referenceAddress = data.head._3.source
          val referenceNetworkStatus = data.head._2
          val referenceHeight = data.head._1
          val matches = data
            .map(
              datum =>
                datum._3.source == referenceAddress || datum._3.destination == referenceAddress || datum._2 == referenceNetworkStatus || datum._1.value <= referenceHeight.value
            )
            .count(_ == true)

          val arguments = data
            .flatMap(
              datum =>
                kryo
                  .serialize(datum._3)
                  .flatMap(
                    bytes =>
                      new RefinedHashable(datum._3)(kryo).hash
                        .map(_ -> (datum._3.source, datum._3.destination, datum._1, datum._2, bytes))
                  )
                  .toOption
            )
            .toMap

          referenceHash
            .map(
              hash =>
                transactionStorage.updateStoredTransactionIndexValues(arguments) >> {
                  implicit val kryoImplicit = kryo

                  val request = BlockSearchRequest(
                    true,
                    false,
                    Some(referenceAddress.value.value),
                    Some(referenceNetworkStatus.value.value),
                    None,
                    None,
                    Some(hash.value),
                    Some(referenceHeight.value.value)
                  )
                  val searchIo = Database
                    .forAsync[IO](dbConfig)
                    .flatMap(implicit db => IO.pure(TransactionIndexSearch.make(transactionStorage)).asResource)

                  searchIo.use(
                    search =>
                      search
                        .searchTransactions(request)
                        .map(
                          _.map(
                            x =>
                              expect.all(
                                x.length == matches,
                                x.forall(
                                  y =>
                                    referenceHash
                                      .flatMap(z => new RefinedHashable(y.signedTransaction)(kryo).hash.map(_ == z))
                                      .toOption
                                      .getOrElse(
                                        false
                                      ) || y.signedTransaction.source == referenceAddress || y.signedTransaction.destination == referenceAddress || y.networkStatus == referenceNetworkStatus || y.height <= referenceHeight.value.value
                                )
                              )
                          )
                        )
                        .toOption
                        .getOrElse(IO.pure(expect(false)))
                  )
                }
            )
            .toOption
            .getOrElse(IO.pure(expect(false)))
      }
    }
  }
}
