package org.tessellation.aci

import java.io.{BufferedOutputStream, FileOutputStream}

import org.mockito.cats.IdiomaticMockitoCats
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito}
import org.scalatest.BeforeAndAfter
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.tessellation.schema.Ω

case class SomeInput(amount: Int) extends Ω

class GeneratePayloadTest
    extends AnyFreeSpec
    with IdiomaticMockito
    with IdiomaticMockitoCats
    with Matchers
    with ArgumentMatchersSugar
    with BeforeAndAfter {

  "save payload to file" in {
    val input = SomeInput(10_000_000)

    val kryo = KryoFactory.createKryoInstance(Map(classOf[SomeInput] -> 101))
    val bytes = kryo.toBytesWithClass(input)

    val bos = new BufferedOutputStream(new FileOutputStream("/tmp/state-channel-input"))
    try {
      bos.write(bytes)
    } finally {
      bos.close()
    }
  }

}
