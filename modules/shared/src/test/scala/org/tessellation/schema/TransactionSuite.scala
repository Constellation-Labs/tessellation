package org.tessellation.schema

import cats.effect.{IO, Resource}

import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.ext.crypto._
import org.tessellation.json.JsonSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction._
import org.tessellation.security._
import org.tessellation.security.hash.Hash
import org.tessellation.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import io.circe.Encoder
import suite.ResourceSuite
import weaver.scalacheck.Checkers

object TransactionSuite extends ResourceSuite with Checkers {

  override type Res = Hasher[IO]

  override def sharedResource: Resource[IO, Res] =
    KryoSerializer
      .forAsync[IO](sharedKryoRegistrar, List.empty, setReferences = true)
      .flatMap { implicit res =>
        JsonSerializer.forSync[IO].asResource.map { implicit json =>
          Hasher.forSync[IO](new HashSelect { def select(ordinal: SnapshotOrdinal): HashLogic = JsonHash })
        }
      }

  def hashWithKryo[A: Encoder](toHash: A): IO[Hash] =
    sharedResource.use(_.hash(toHash))

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

  test("Hash for a new Transaction schema should be the same as hash for old Transaction schema") { implicit res =>
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

    transaction.hash.map(expect.same(expectedHash, _))
  }
}
