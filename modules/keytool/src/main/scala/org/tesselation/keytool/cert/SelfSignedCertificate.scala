package org.tesselation.keytool.cert

import java.math.BigInteger
import java.security.cert.X509Certificate
import java.security.{KeyPair, SecureRandom, Security}
import java.util.{Calendar, Date}

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.spongycastle.asn1.x500.X500Name
import org.spongycastle.asn1.x509.SubjectPublicKeyInfo
import org.spongycastle.cert.X509v3CertificateBuilder
import org.spongycastle.cert.jcajce.JcaX509CertificateConverter
import org.spongycastle.operator.jcajce.JcaContentSignerBuilder

object SelfSignedCertificate {

  def makeBouncyCastleProvider[F[_]: Async]: F[BouncyCastleProvider] =
    Async[F].delay {
      new BouncyCastleProvider()
    }.flatTap { provider =>
      Async[F].delay { Security.addProvider(provider) }
    }

  def generate[F[_]: Async](dn: String, pair: KeyPair, days: Int, algorithm: String): F[X509Certificate] =
    for {
      provider <- makeBouncyCastleProvider[F]
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
