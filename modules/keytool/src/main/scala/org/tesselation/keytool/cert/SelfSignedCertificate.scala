package org.tesselation.keytool.cert

import java.math.BigInteger
import java.security.cert.X509Certificate
import java.security.{KeyPair, SecureRandom}
import java.util.{Calendar, Date}

import cats.Applicative
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tesselation.keytool.security.SecurityProvider

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

object SelfSignedCertificate {

  def generate[F[_]: Async: SecurityProvider](
    dn: String,
    pair: KeyPair,
    days: Int,
    algorithm: String
  ): F[X509Certificate] =
    for {
      _ <- Applicative[F].unit
      provider = SecurityProvider[F].provider
      owner = new X500Name(dn)
      rnd <- Async[F].delay { new SecureRandom() }
      serial = new BigInteger(64, rnd)
      (notBefore, notAfter) = periodFromDays(days)
      publicKeyInfo <- Async[F].delay { SubjectPublicKeyInfo.getInstance(pair.getPublic.getEncoded) }
      builder <- Async[F].delay {
        new X509v3CertificateBuilder(owner, serial, notBefore, notAfter, owner, publicKeyInfo)
      }
      signer <- Async[F].delay { new JcaContentSignerBuilder(algorithm).setProvider(provider).build(pair.getPrivate) }
      certificateHolder <- Async[F].delay { builder.build(signer) }
      selfSignedCertificate <- Async[F].delay { new JcaX509CertificateConverter().getCertificate(certificateHolder) }
    } yield selfSignedCertificate

  private def periodFromDays(days: Int): (Date, Date) = {
    val notBefore = new Date()
    val calendar = Calendar.getInstance
    calendar.setTime(notBefore)
    calendar.add(Calendar.DAY_OF_MONTH, days)
    val notAfter = calendar.getTime
    (notBefore, notAfter)
  }

}
