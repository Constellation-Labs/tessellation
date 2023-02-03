package org.tessellation.schema

import cats.effect.{IO, Resource}

import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction._
import org.tessellation.security.hash.Hash
import org.tessellation.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import suite.ResourceSuite
import weaver.scalacheck.Checkers

object TransactionSuite extends ResourceSuite with Checkers {

  override type Res = KryoSerializer[IO]

  override def sharedResource: Resource[IO, Res] =
    KryoSerializer.forAsync[IO](sharedKryoRegistrar, List.empty, setReferences = true)

  def hashWithKryo(toHash: AnyRef): IO[Hash] =
    KryoSerializer
      .forAsync[IO](sharedKryoRegistrar, List.empty, setReferences = true)
      .use { kryo =>
        implicit val k = kryo
        toHash.hashF
      }

  test("Transaction's representation used for hashing should follow expected format") {
    val transaction = Transaction(
      Address("DAG2EUdecqFwEGcgAcH1ac2wrsg8acrgGwrQabcd"),
      Address("DAG2EUdecqFwEGcgAcH1ac2wrsg8acrgGwrQefgh"),
      TransactionAmount(10L),
      TransactionFee(3L),
      TransactionReference(TransactionOrdinal(2L), Hash("someHash")),
      TransactionSalt(1234L)
    )

    val expectedToEncode =
      "2" +
        "40" +
        "DAG2EUdecqFwEGcgAcH1ac2wrsg8acrgGwrQabcd" +
        "40" +
        "DAG2EUdecqFwEGcgAcH1ac2wrsg8acrgGwrQefgh" +
        "1" +
        "a" +
        "8" +
        "someHash" +
        "1" +
        "2" +
        "1" +
        "3" +
        "3" +
        "4d2"

    IO.pure(expect.same(expectedToEncode, transaction.toEncode))
  }

  test("Hash for a new Transaction schema should be the same as hash for old Transaction schema") { implicit kryo =>
    val expectedHash = Hash("1017a072225263aa502d9dcd22a04455f9b063cc19166356be50b4730afc44f6")

    val transaction = Transaction(
      Address("DAG53ho9ssY8KYQdjxsWPYgNbDJ1YqM2RaPDZebU"),
      Address("DAG53ho9ssY8KYQdjxsWPYgNbDJ1YqM2RaPDZebU"),
      TransactionAmount(100000000L),
      TransactionFee(0L),
      TransactionReference(
        TransactionOrdinal(1L),
        Hash("d5149e2339ced3b285062dc403ba0c89642792a462476dc35f63e0328b3cac52")
      ),
      TransactionSalt(-6326757804706870905L)
    )

    transaction.hashF.map(expect.same(expectedHash, _))
  }
}
