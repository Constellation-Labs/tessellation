package org.tessellation.rosetta.server.model.dag

import scala.reflect.io.Path

object GenerateCodecs {

  def run(): Unit =
    scala.tools.nsc.io.File
      .apply(Path("modules/rosetta/src/main/scala/org/tessellation/rosetta/server/model"))
      .toDirectory
      .list
      .filter { _.isFile }
      .foreach { p =>
        val name = p.name.replaceAll(".scala", "")
        val gen = s"""implicit lazy val ${name}Encoder = deriveConfiguredMagnoliaEncoder[${name}]
                      |implicit lazy val ${name}Decoder = deriveConfiguredMagnoliaDecoder[${name}]""".stripMargin
        println(gen)
      }
  //
//  def main(args: Array[String]): Unit = {
//    run()
//  }
//
}
