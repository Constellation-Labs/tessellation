import io.airlift.airline.Cli
import org.openapitools.codegen.cmd.Generate

object Tasks {
  def generateRosettaModels = {
    val modelPackageName = "org.tessellation.rosetta.api"
    val generator = "scala-httpclient-deprecated"
    val templateDirectory = "modules/rosetta/src/main/resources/templates"
    val outputDirectory = "modules/rosetta/"
    val input = "https://raw.githubusercontent.com/coinbase/rosetta-specifications/master/api.yaml"

    val builder = Cli.builder[Runnable]("openapi-generator-cli").withCommands(classOf[Generate])
    val generate = builder.build().parse(
      "generate",
      "--global-property", "models",
      "--additional-properties", s"modelPackage=$modelPackageName",
      "-g", generator,
      "-t", templateDirectory,
      "-o", outputDirectory,
      "-i", input
    )
    generate.run()
  }
}
