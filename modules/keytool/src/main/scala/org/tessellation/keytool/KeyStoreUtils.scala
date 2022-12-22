package org.tessellation.keytool

import java.io._
import java.nio.charset.Charset
import java.nio.file.FileAlreadyExistsException
import java.security.cert.Certificate
import java.security.{KeyPair, KeyStore, PrivateKey}

import cats.effect.{Async, Resource}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.keytool.cert.{DistinguishedName, SelfSignedCertificate}
import org.tessellation.schema.security._
import org.tessellation.schema.security.hex.Hex
import org.tessellation.schema.security.key.ops._
import org.tessellation.schema.security.signature.Signing

import io.estatico.newtype.ops._

object KeyStoreUtils {

  private val storeType: String = "PKCS12"
  private val storeExtension: String = "p12"
  private val singlePasswordKeyStoreSuffix: String = "_v2"
  private val privateKeyHexName: String = "id_ecdsa.hex"

  private[keytool] def exportPrivateKeyAsHex[F[_]: Async: SecurityProvider](
    path: String,
    alias: String,
    storePassword: Array[Char],
    keyPassword: Array[Char]
  ): F[Hex] =
    for {
      keyPair <- readKeyPairFromStore(path, alias, storePassword, keyPassword)
      hex = keyPair.getPrivate.toHex
      _ <- writer(pathDir(path) + privateKeyHexName)
        .use(os =>
          Async[F].delay {
            os.write(hex.coerce.getBytes(Charset.forName("UTF-8")))
          }
        )
    } yield hex

  private[keytool] def migrateKeyStoreToSinglePassword[F[_]: Async: SecurityProvider](
    path: String,
    alias: String,
    storePassword: Array[Char],
    keyPassword: Array[Char],
    distinguishedName: DistinguishedName,
    certificateValidityDays: Long
  ): F[KeyStore] =
    for {
      keyPair <- readKeyPairFromStore(path, alias, storePassword, keyPassword)
      newKeyStore <- writeKeyPairToStore(
        keyPair,
        withSinglePasswordSuffix(path),
        alias,
        storePassword,
        distinguishedName,
        certificateValidityDays
      )
    } yield newKeyStore

  private[keytool] def generateKeyPairToStore[F[_]: Async: SecurityProvider](
    path: String,
    alias: String,
    password: Array[Char],
    distinguishedName: DistinguishedName,
    certificateValidityDays: Long
  ): F[KeyStore] =
    KeyPairGenerator
      .makeKeyPair[F]
      .flatMap(writeKeyPairToStore(_, path, alias, password, distinguishedName, certificateValidityDays))

  /** Puts existing to new keyStore at path
    */
  private[keytool] def writeKeyPairToStore[F[_]: Async: SecurityProvider](
    keyPair: KeyPair,
    path: String,
    alias: String,
    password: Array[Char],
    distinguishedName: DistinguishedName,
    certificateValidityDays: Long
  ): F[KeyStore] =
    writer(withExtension(path))
      .use(stream =>
        for {
          keyStore <- createEmptyKeyStore(password)
          chain <- generateCertificateChain(distinguishedName, certificateValidityDays, keyPair)
          _ <- setKeyEntry(alias, keyPair, password, chain)(keyStore)
          _ <- store(stream, password)(keyStore)
        } yield keyStore
      )

  def readKeyPairFromStore[F[_]: Async: SecurityProvider](
    path: String,
    alias: String,
    storepass: Array[Char],
    keypass: Array[Char]
  ): F[KeyPair] =
    reader(path)
      .evalMap(
        if (storepass.sameElements(keypass))
          unlockKeyStore[F](storepass)
        else
          unlockLegacyKeyStore[F](storepass)
      )
      .evalMap(unlockKeyPair[F](alias, keypass))
      .use(_.pure[F])

  private def reader[F[_]: Async](path: String): Resource[F, FileInputStream] =
    Resource.fromAutoCloseable(Async[F].delay {
      new FileInputStream(path)
    })

  private def writer[F[_]: Async](path: String): Resource[F, FileOutputStream] =
    Resource.fromAutoCloseable(Async[F].delay {
      val file = new File(path)
      if (file.exists()) {
        throw new FileAlreadyExistsException(file.getPath)
      }
      new FileOutputStream(file, false)
    })

  private def generateCertificateChain[F[_]: Async: SecurityProvider](
    distinguishedName: DistinguishedName,
    certificateValidityDays: Long,
    keyPair: KeyPair
  ): F[Array[Certificate]] =
    SelfSignedCertificate
      .generate(distinguishedName, keyPair, certificateValidityDays, Signing.defaultSignFunc)
      .map(Array(_))

  private def unlockKeyStore[F[_]: Async: SecurityProvider](
    password: Array[Char]
  )(stream: FileInputStream): F[KeyStore] =
    Async[F].delay(KeyStore.getInstance(storeType, SecurityProvider[F].provider)).flatTap { keyStore =>
      Async[F].delay(keyStore.load(stream, password))
    }

  /** Just for backward compatibility. Uses regular Security provider order instead of explicit one
    */
  private def unlockLegacyKeyStore[F[_]: Async](password: Array[Char])(
    stream: FileInputStream
  ): F[KeyStore] =
    Async[F].delay(KeyStore.getInstance(storeType)).flatTap { keyStore =>
      Async[F].delay(keyStore.load(stream, password))
    }

  private def createEmptyKeyStore[F[_]: Async: SecurityProvider](password: Array[Char]): F[KeyStore] =
    Async[F].delay(KeyStore.getInstance(storeType, SecurityProvider[F].provider)).flatTap { keyStore =>
      Async[F].delay(keyStore.load(null, password))
    }

  private def unlockKeyPair[F[_]: Async](alias: String, keyPassword: Array[Char])(keyStore: KeyStore): F[KeyPair] =
    for {
      privateKey <- Async[F].delay(keyStore.getKey(alias, keyPassword).asInstanceOf[PrivateKey])
      publicKey <- Async[F].delay(keyStore.getCertificate(alias).getPublicKey)
    } yield new KeyPair(publicKey, privateKey)

  private def setKeyEntry[F[_]: Async](
    alias: String,
    keyPair: KeyPair,
    keyPassword: Array[Char],
    chain: Array[Certificate]
  )(keyStore: KeyStore): F[KeyStore] =
    Async[F].delay {
      keyStore.setKeyEntry(alias, keyPair.getPrivate, keyPassword, chain)
      keyStore
    }

  private def store[F[_]: Async](stream: FileOutputStream, storePassword: Array[Char])(
    keyStore: KeyStore
  ): F[KeyStore] = Async[F].delay {
    keyStore.store(stream, storePassword)
    keyStore
  }

  private def pathDir(path: String): String = {
    val slashIndex = path.lastIndexOf("/")
    if (slashIndex > 0) {
      path.substring(0, slashIndex + 1)
    } else {
      ""
    }
  }

  private def withSinglePasswordSuffix(path: String): String =
    withExtension(withoutExtension(path) + singlePasswordKeyStoreSuffix)

  private def withExtension(path: String): String =
    if (path.endsWith(storeExtension)) path else s"$path.$storeExtension"

  private def withoutExtension(path: String): String = {
    val dotIndex = path.lastIndexOf(".")
    if (dotIndex > 0) {
      path.substring(0, dotIndex)
    } else {
      path
    }
  }

}
