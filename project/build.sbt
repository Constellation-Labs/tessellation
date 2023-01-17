val openApiGeneratorVersion = "6.2.1"

lazy val metaBuild = (project in file(".")).settings(
  libraryDependencies ++= Seq(
    "org.openapitools" % "openapi-generator-core" % openApiGeneratorVersion,
    "org.openapitools" % "openapi-generator-cli" % openApiGeneratorVersion
  )
)
