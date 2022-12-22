package org.tessellation.keytool.cert

import java.math.BigInteger
import java.security.KeyPair
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.schema.security.{SecureRandom, SecurityProvider}

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

object SelfSignedCertificate {

  def generate[F[_]: Async: SecurityProvider](
    dn: DistinguishedName,
    pair: KeyPair,
    days: Long,
    algorithm: String
  ): F[X509Certificate] =
    for {
      rnd <- SecureRandom.get[F]
      provider = SecurityProvider[F].provider
      owner = new X500Name(dn.toX509DirName)
      serial = new BigInteger(64, rnd)
      (notBefore, notAfter) = periodFromDays(days)
      publicKeyInfo <- Async[F].delay {
        SubjectPublicKeyInfo.getInstance(pair.getPublic.getEncoded)
      }
      builder <- Async[F].delay {
        new X509v3CertificateBuilder(owner, serial, notBefore, notAfter, owner, publicKeyInfo)
      }
      signer <- Async[F].delay {
        new JcaContentSignerBuilder(algorithm).setProvider(provider).build(pair.getPrivate)
      }
      certificateHolder <- Async[F].delay(builder.build(signer))
      selfSignedCertificate <- Async[F].delay {
        new JcaX509CertificateConverter().setProvider(provider).getCertificate(certificateHolder)
      }
    } yield selfSignedCertificate

  private def periodFromDays(days: Long): (Date, Date) = {
    val notBefore = Instant.now()
    val notAfter = notBefore.plus(days, ChronoUnit.DAYS)
    (Date.from(notBefore), Date.from(notAfter))
  }

}
