package org.tessellation.dag.l1.infrastructure.transaction.storage

import cats.effect.{IO, Resource}

import org.tessellation.dag.l1.config.types.DBConfig
import org.tessellation.dag.l1.domain.transaction.storage.TransactionIndexStorage
import org.tessellation.dag.l1.infrastructure.db.Database
import org.tessellation.dag.l1.infrastructure.transaction.storage.SignedTransactionGenerator.signedTransactionGen
import org.tessellation.dag.l1.rosetta.model.network.NetworkStatus
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

object TransactionIndexDbStorageSuite extends SimpleIOSuite with Checkers {
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
          val heightMatches = data.map(datum => datum._1.value <= data.head._1.value).count(_ == true)

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

          transactionStorage.updateStoredTransactionIndexValues(arguments) >>
            transactionStorage
              .getTransactionIndexValuesAnd(None, None, None, Some(data.head._1.value.value), None, None)
              .map(
                _.map(
                  y =>
                    expect.all(
                      y.length == heightMatches,
                      y.forall(_.height <= data.head._1.value.value)
                    )
                )
              )
              .toOption
              .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by id") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash.toOption

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
                transactionStorage.updateStoredTransactionIndexValues(arguments) >>
                  transactionStorage
                    .getTransactionIndexValuesAnd(Some(hash), None, None, None, None, None)
                    .map(
                      _.map(
                        y =>
                          expect.all(
                            y.length == 1,
                            y.forall(
                              z =>
                                new RefinedHashable(z.signedTransaction)(kryo).hash
                                  .map(_ == hash)
                                  .toOption
                                  .getOrElse(false)
                            )
                          )
                      )
                    )
                    .toOption
                    .getOrElse(IO.pure(expect(false)))
            )
            .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by id, failed height") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash.toOption

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
                transactionStorage.updateStoredTransactionIndexValues(arguments) >>
                  transactionStorage
                    .getTransactionIndexValuesAnd(Some(hash), None, None, Some(Long.MinValue), None, None)
                    .map(
                      _.map(y => expect.same(y.length, 0))
                    )
                    .toOption
                    .getOrElse(IO.pure(expect(false)))
            )
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

          transactionStorage.updateStoredTransactionIndexValues(arguments) >>
            transactionStorage
              .getTransactionIndexValuesAnd(None, Some(referenceAddress), None, None, None, None)
              .map(
                _.map(
                  y =>
                    expect.all(
                      y.length == matches,
                      y.forall(
                        z => z.signedTransaction.source == referenceAddress || z.signedTransaction.destination == referenceAddress
                      )
                    )
                )
              )
              .toOption
              .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by network status") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val matches = data.map(datum => datum._2.value == data.head._2.value).count(_ == true)

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

          transactionStorage.updateStoredTransactionIndexValues(arguments) >>
            transactionStorage
              .getTransactionIndexValuesAnd(None, None, Some(data.head._2), None, None, None)
              .map(
                _.map(
                  y =>
                    expect.all(
                      y.length == matches,
                      y.forall(_.networkStatus == data.head._2)
                    )
                )
              )
              .toOption
              .getOrElse(IO.pure(expect(false)))
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

          transactionStorage.updateStoredTransactionIndexValues(arguments) >>
            transactionStorage
              .getTransactionIndexValuesAnd(None, None, Some(NetworkStatus.accepted), None, None, Some(2))
              .map(
                _.map(
                  y =>
                    expect.all(
                      y.length == 2,
                      y.forall(_.networkStatus == NetworkStatus.accepted)
                    )
                )
              )
              .toOption
              .getOrElse(IO.pure(expect(false)))
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

          transactionStorage.updateStoredTransactionIndexValues(arguments) >>
            transactionStorage
              .getTransactionIndexValuesAnd(None, None, Some(NetworkStatus.accepted), None, Some(1), Some(Int.MaxValue))
              .map(
                _.map(
                  y =>
                    expect.all(
                      y.length == 2,
                      y.forall(_.networkStatus == NetworkStatus.accepted)
                    )
                )
              )
              .toOption
              .getOrElse(IO.pure(expect(false)))
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
            val queryResults = transactionStorage
              .getTransactionIndexValuesAnd(None, None, Some(NetworkStatus.accepted), None, Some(1), None)

            IO.pure(expect(queryResults.isLeft))
          }
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by id and address") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash.toOption
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
                transactionStorage.updateStoredTransactionIndexValues(arguments) >>
                  transactionStorage
                    .getTransactionIndexValuesAnd(Some(hash), Some(referenceAddress), None, None, None, None)
                    .map(
                      _.map(
                        y =>
                          expect.all(
                            y.length == 1,
                            y.forall(
                              z =>
                                new RefinedHashable(z.signedTransaction)(kryo).hash
                                  .map(_ == hash)
                                  .toOption
                                  .getOrElse(false) && z.signedTransaction.source == referenceAddress
                            )
                          )
                      )
                    )
                    .toOption
                    .getOrElse(IO.pure(expect(false)))
            )
            .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by id and network status") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash.toOption
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
                transactionStorage.updateStoredTransactionIndexValues(arguments) >>
                  transactionStorage
                    .getTransactionIndexValuesAnd(Some(hash), None, Some(referenceNetworkStatus), None, None, None)
                    .map(
                      _.map(
                        y =>
                          expect.all(
                            y.length == 1,
                            y.forall(
                              z =>
                                new RefinedHashable(z.signedTransaction)(kryo).hash
                                  .map(_ == hash)
                                  .toOption
                                  .getOrElse(false) && z.networkStatus == referenceNetworkStatus
                            )
                          )
                      )
                    )
                    .toOption
                    .getOrElse(IO.pure(expect(false)))
            )
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
              x => (x._3.source == referenceAddress || x._3.destination == referenceAddress) && x._2 == referenceNetworkStatus
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

          transactionStorage.updateStoredTransactionIndexValues(arguments) >>
            transactionStorage
              .getTransactionIndexValuesAnd(
                None,
                Some(referenceAddress),
                Some(referenceNetworkStatus),
                None,
                None,
                None
              )
              .map(
                _.map(
                  y =>
                    expect.all(
                      y.length == matches,
                      y.forall(
                        z =>
                          (z.signedTransaction.source == referenceAddress || z.signedTransaction.destination == referenceAddress) && z.networkStatus == referenceNetworkStatus
                      )
                    )
                )
              )
              .toOption
              .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by id, address, and network status"
  ) {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash.toOption
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
                transactionStorage.updateStoredTransactionIndexValues(arguments) >>
                  transactionStorage
                    .getTransactionIndexValuesAnd(
                      Some(hash),
                      Some(referenceAddress),
                      Some(referenceNetworkStatus),
                      None,
                      None,
                      None
                    )
                    .map(
                      _.map(
                        y =>
                          expect.all(
                            y.length == 1,
                            y.forall(
                              z =>
                                new RefinedHashable(z.signedTransaction)(kryo).hash
                                  .map(_ == hash)
                                  .toOption
                                  .getOrElse(false) && z.signedTransaction.source == referenceAddress && z.networkStatus == referenceNetworkStatus
                            )
                          )
                      )
                    )
                    .toOption
                    .getOrElse(IO.pure(expect(false)))
            )
            .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by height, or") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val heightMatches = data.map(datum => datum._1.value <= data.head._1.value).count(_ == true)

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

          transactionStorage.updateStoredTransactionIndexValues(arguments) >>
            transactionStorage
              .getTransactionIndexValuesOr(None, None, None, Some(data.head._1.value.value), None, None)
              .map(
                _.map(
                  y =>
                    expect.all(
                      y.length == heightMatches,
                      y.forall(_.height <= data.head._1.value.value)
                    )
                )
              )
              .toOption
              .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by id, or") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash.toOption

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
                transactionStorage.updateStoredTransactionIndexValues(arguments) >>
                  transactionStorage
                    .getTransactionIndexValuesOr(Some(hash), None, None, None, None, None)
                    .map(
                      _.map(
                        y =>
                          expect.all(
                            y.length == 1,
                            y.forall(
                              z =>
                                new RefinedHashable(z.signedTransaction)(kryo).hash
                                  .map(_ == hash)
                                  .toOption
                                  .getOrElse(false)
                            )
                          )
                      )
                    )
                    .toOption
                    .getOrElse(IO.pure(expect(false)))
            )
            .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by address, or") {
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

          transactionStorage.updateStoredTransactionIndexValues(arguments) >>
            transactionStorage
              .getTransactionIndexValuesOr(None, Some(referenceAddress), None, None, None, None)
              .map(
                _.map(
                  y =>
                    expect.all(
                      y.length == matches,
                      y.forall(
                        z => z.signedTransaction.source == referenceAddress || z.signedTransaction.destination == referenceAddress
                      )
                    )
                )
              )
              .toOption
              .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by network status, or") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val matches = data.map(datum => datum._2.value == data.head._2.value).count(_ == true)

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

          transactionStorage.updateStoredTransactionIndexValues(arguments) >>
            transactionStorage
              .getTransactionIndexValuesOr(None, None, Some(data.head._2), None, None, None)
              .map(
                _.map(
                  y =>
                    expect.all(
                      y.length == matches,
                      y.forall(_.networkStatus == data.head._2)
                    )
                )
              )
              .toOption
              .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by network status, or, limited"
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

          transactionStorage.updateStoredTransactionIndexValues(arguments) >>
            transactionStorage
              .getTransactionIndexValuesOr(None, None, Some(NetworkStatus.accepted), None, None, Some(2))
              .map(
                _.map(
                  y =>
                    expect.all(
                      y.length == 2,
                      y.forall(_.networkStatus == NetworkStatus.accepted)
                    )
                )
              )
              .toOption
              .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by network status, or, offset"
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

          transactionStorage.updateStoredTransactionIndexValues(arguments) >>
            transactionStorage
              .getTransactionIndexValuesOr(None, None, Some(NetworkStatus.accepted), None, Some(1), Some(Int.MaxValue))
              .map(
                _.map(
                  y =>
                    expect.all(
                      y.length == 2,
                      y.forall(_.networkStatus == NetworkStatus.accepted)
                    )
                )
              )
              .toOption
              .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by network status. or, offset, error"
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
            val queryResults = transactionStorage
              .getTransactionIndexValuesOr(None, None, Some(NetworkStatus.accepted), None, Some(1), None)

            IO.pure(expect(queryResults.isLeft))
          }
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by id or address") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash.toOption
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
                transactionStorage.updateStoredTransactionIndexValues(arguments) >>
                  transactionStorage
                    .getTransactionIndexValuesOr(Some(hash), Some(referenceAddress), None, None, None, None)
                    .map(
                      _.map(
                        y =>
                          expect.all(
                            y.length == matches,
                            y.forall(
                              z =>
                                (z.signedTransaction.source == referenceAddress || z.signedTransaction.destination == referenceAddress) || new RefinedHashable(
                                  z.signedTransaction
                                )(kryo).hash.map(_ == hash).toOption.getOrElse(false)
                            )
                          )
                      )
                    )
                    .toOption
                    .getOrElse(IO.pure(expect(false)))
            )
            .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by id or network status") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash.toOption
          val referenceNetworkAddress = data.head._2
          val matches = data.map(datum => datum._2.value == referenceNetworkAddress.value).count(_ == true)

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
                transactionStorage.updateStoredTransactionIndexValues(arguments) >>
                  transactionStorage
                    .getTransactionIndexValuesOr(Some(hash), None, Some(referenceNetworkAddress), None, None, None)
                    .map(
                      _.map(
                        y =>
                          expect.all(
                            y.length == matches,
                            y.forall(
                              z =>
                                new RefinedHashable(z.signedTransaction)(kryo).hash
                                  .map(_ == hash)
                                  .toOption
                                  .getOrElse(false) || z.networkStatus == referenceNetworkAddress
                            )
                          )
                      )
                    )
                    .toOption
                    .getOrElse(IO.pure(expect(false)))
            )
            .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by id or height") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash.toOption
          val referenceHeight = data.head._1
          val matches = data.map(datum => datum._1.value <= referenceHeight.value).count(_ == true)

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
                transactionStorage.updateStoredTransactionIndexValues(arguments) >>
                  transactionStorage
                    .getTransactionIndexValuesOr(Some(hash), None, None, Some(referenceHeight.value.value), None, None)
                    .map(
                      _.map(
                        y =>
                          expect.all(
                            y.length == matches,
                            y.forall(
                              z =>
                                new RefinedHashable(z.signedTransaction)(kryo).hash
                                  .map(_ == hash)
                                  .toOption
                                  .getOrElse(false) || z.height <= referenceHeight.value.value
                            )
                          )
                      )
                    )
                    .toOption
                    .getOrElse(IO.pure(expect(false)))
            )
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
              datum =>
                datum._3.source == referenceAddress || datum._3.destination == referenceAddress || datum._2.value == referenceNetworkStatus.value
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

          transactionStorage.updateStoredTransactionIndexValues(arguments) >>
            transactionStorage
              .getTransactionIndexValuesOr(None, Some(referenceAddress), Some(referenceNetworkStatus), None, None, None)
              .map(
                _.map(
                  y =>
                    expect.all(
                      y.length == matches,
                      y.forall(
                        z =>
                          z.signedTransaction.source == referenceAddress || z.signedTransaction.destination == referenceAddress || z.networkStatus == referenceNetworkStatus
                      )
                    )
                )
              )
              .toOption
              .getOrElse(IO.pure(expect(false)))
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

          transactionStorage.updateStoredTransactionIndexValues(arguments) >>
            transactionStorage
              .getTransactionIndexValuesOr(
                None,
                Some(referenceAddress),
                None,
                Some(referenceHeight.value.value),
                None,
                None
              )
              .map(
                _.map(
                  y =>
                    expect.all(
                      y.length == matches,
                      y.forall(
                        z =>
                          z.signedTransaction.source == referenceAddress || z.signedTransaction.destination == referenceAddress || z.height <= referenceHeight.value.value
                      )
                    )
                )
              )
              .toOption
              .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by network address or height"
  ) {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          // val referenceAddress = data.head._3.source
          val referenceNetworkStatus = data.head._2
          val referenceHeight = data.head._1
          val matches = data
            .map(datum => datum._2.value == referenceNetworkStatus.value || datum._1.value <= referenceHeight.value)
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

          transactionStorage.updateStoredTransactionIndexValues(arguments) >>
            transactionStorage
              .getTransactionIndexValuesOr(
                None,
                None,
                Some(referenceNetworkStatus),
                Some(referenceHeight.value.value),
                None,
                None
              )
              .map(
                _.map(
                  y =>
                    expect.all(
                      y.length == matches,
                      y.forall(
                        z => z.networkStatus == referenceNetworkStatus || z.height <= referenceHeight.value.value
                      )
                    )
                )
              )
              .toOption
              .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by id, address, or network status"
  ) {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash.toOption
          val referenceAddress = data.head._3.source
          val referenceNetworkStatus = data.head._2
          val matches = data
            .map(
              datum =>
                datum._3.source == referenceAddress || datum._3.destination == referenceAddress || datum._2.value == referenceNetworkStatus.value
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
                transactionStorage.updateStoredTransactionIndexValues(arguments) >>
                  transactionStorage
                    .getTransactionIndexValuesOr(
                      Some(hash),
                      Some(referenceAddress),
                      Some(referenceNetworkStatus),
                      None,
                      None,
                      None
                    )
                    .map(
                      _.map(
                        y =>
                          expect.all(
                            y.length == matches,
                            y.forall(
                              z =>
                                new RefinedHashable(z.signedTransaction)(kryo).hash
                                  .map(_ == hash)
                                  .toOption
                                  .getOrElse(
                                    false
                                  ) || z.signedTransaction.source == referenceAddress || z.signedTransaction.destination == referenceAddress || z.networkStatus == referenceNetworkStatus
                            )
                          )
                      )
                    )
                    .toOption
                    .getOrElse(IO.pure(expect(false)))
            )
            .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test("Test writing transaction index values to the database - get transaction index values by id, address, or height") {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash.toOption
          val referenceAddress = data.head._3.source
          val referenceHeight = data.head._1
          val matches = data
            .map(
              datum =>
                datum._3.source == referenceAddress || datum._3.destination == referenceAddress || datum._1.value <= referenceHeight.value.value
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
                transactionStorage.updateStoredTransactionIndexValues(arguments) >>
                  transactionStorage
                    .getTransactionIndexValuesOr(
                      Some(hash),
                      Some(referenceAddress),
                      None,
                      Some(referenceHeight.value.value),
                      None,
                      None
                    )
                    .map(
                      _.map(
                        y =>
                          expect.all(
                            y.length == matches,
                            y.forall(
                              z =>
                                new RefinedHashable(z.signedTransaction)(kryo).hash
                                  .map(_ == hash)
                                  .toOption
                                  .getOrElse(
                                    false
                                  ) || z.signedTransaction.source == referenceAddress || z.signedTransaction.destination == referenceAddress || z.height <= referenceHeight.value.value
                            )
                          )
                      )
                    )
                    .toOption
                    .getOrElse(IO.pure(expect(false)))
            )
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
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash.toOption
          val referenceNetworkStatus = data.head._2
          val referenceHeight = data.head._1
          val matches = data
            .map(datum => datum._2 == referenceNetworkStatus || datum._1.value <= referenceHeight.value.value)
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
                transactionStorage.updateStoredTransactionIndexValues(arguments) >>
                  transactionStorage
                    .getTransactionIndexValuesOr(
                      Some(hash),
                      None,
                      Some(referenceNetworkStatus),
                      Some(referenceHeight.value.value),
                      None,
                      None
                    )
                    .map(
                      _.map(
                        y =>
                          expect.all(
                            y.length == matches,
                            y.forall(
                              z =>
                                new RefinedHashable(z.signedTransaction)(kryo).hash
                                  .map(_ == hash)
                                  .toOption
                                  .getOrElse(false) || z.networkStatus == referenceNetworkStatus || z.height <= referenceHeight.value.value
                            )
                          )
                      )
                    )
                    .toOption
                    .getOrElse(IO.pure(expect(false)))
            )
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
                datum._3.source == referenceAddress || datum._3.destination == referenceAddress || datum._2 == referenceNetworkStatus || datum._1.value <= referenceHeight.value.value
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

          transactionStorage.updateStoredTransactionIndexValues(arguments) >>
            transactionStorage
              .getTransactionIndexValuesOr(
                None,
                Some(referenceAddress),
                Some(referenceNetworkStatus),
                Some(referenceHeight.value.value),
                None,
                None
              )
              .map(
                _.map(
                  y =>
                    expect.all(
                      y.length == matches,
                      y.forall(
                        z =>
                          z.signedTransaction.source == referenceAddress || z.signedTransaction.destination == referenceAddress || z.networkStatus == referenceNetworkStatus || z.height <= referenceHeight.value.value
                      )
                    )
                )
              )
              .toOption
              .getOrElse(IO.pure(expect(false)))
      }
    }
  }

  test(
    "Test writing transaction index values to the database - get transaction index values by id, address, network status, or height"
  ) {
    forall(generatedData) { data =>
      testResource.use {
        case (transactionStorage, kryo) =>
          val referenceHash = new RefinedHashable(data.head._3)(kryo).hash.toOption
          val referenceAddress = data.head._3.source
          val referenceNetworkStatus = data.head._2
          val referenceHeight = data.head._1
          val matches = data
            .map(
              datum =>
                datum._3.source == referenceAddress || datum._3.destination == referenceAddress || datum._2 == referenceNetworkStatus || datum._1.value <= referenceHeight.value.value
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
                transactionStorage.updateStoredTransactionIndexValues(arguments) >>
                  transactionStorage
                    .getTransactionIndexValuesOr(
                      Some(hash),
                      Some(referenceAddress),
                      Some(referenceNetworkStatus),
                      Some(referenceHeight.value.value),
                      None,
                      None
                    )
                    .map(
                      _.map(
                        y =>
                          expect.all(
                            y.length == matches,
                            y.forall(
                              z =>
                                new RefinedHashable(z.signedTransaction)(kryo).hash
                                  .map(_ == hash)
                                  .toOption
                                  .getOrElse(
                                    false
                                  ) || z.signedTransaction.source == referenceAddress || z.signedTransaction.destination == referenceAddress || z.networkStatus == referenceNetworkStatus || z.height <= referenceHeight.value.value
                            )
                          )
                      )
                    )
                    .toOption
                    .getOrElse(IO.pure(expect(false)))
            )
            .getOrElse(IO.pure(expect(false)))
      }
    }
  }
}
