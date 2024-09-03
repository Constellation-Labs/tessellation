package io.constellationnetwork.keytool.cert

case class DistinguishedName(
  commonName: Option[String],
  organization: Option[String],
  organizationalUnit: Option[String],
  locality: Option[String],
  stateOrProvince: Option[String],
  country: Option[String]
) {

  def toX509DirName: String = {
    val mapping = Map[String, Option[String]](
      "CN" -> commonName,
      "OU" -> organizationalUnit,
      "O" -> organization,
      "L" -> locality,
      "ST" -> stateOrProvince,
      "C" -> country
    )

    mapping.filter { case (_, v) => v.isDefined }.map { case (k, v) => s"$k=${v.get}" }
      .mkString(",")
  }
}

object DistinguishedName {

  def apply(
    commonName: String = null,
    organization: String = null,
    organizationalUnit: String = null,
    locality: String = null,
    stateOrProvince: String = null,
    country: String = null
  ): DistinguishedName =
    DistinguishedName(
      Option(commonName),
      Option(organization),
      Option(organizationalUnit),
      Option(locality),
      Option(stateOrProvince),
      Option(country)
    )
}
