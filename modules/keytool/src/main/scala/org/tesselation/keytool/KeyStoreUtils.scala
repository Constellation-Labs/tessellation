package org.tesselation.keytool

import java.io._
import java.nio.charset.Charset
import java.nio.file.FileAlreadyExistsException
import java.security._
import java.security.cert.Certificate

import cats.data.EitherT
import cats.effect.{Async, Resource}
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tesselation.keytool.cert.{DistinguishedName, SelfSignedCertificate}
import org.tesselation.keytool.security.{KeyProvider, SecurityProvider, Signing}

import org.bouncycastle.util.io.pem.{PemObject, PemWriter}

object KeyStoreUtils {

  private val storeType: String = "PKCS12"
  private val storeExtension: String = "p12"
  private val singlePasswordKeyStoreSuffix: String = "_v2"
  private val privateKeyHexName: String = "id_ecdsa.hex"

  private[keytool] def readFromFileStream[F[_]: Async, T](
    dataPath: String,
    streamParser: FileInputStream => F[T]
  ): EitherT[F, Throwable, T] =
    reader(dataPath)
      .use(streamParser)
      .attemptT

  private[keytool] def storeWithFileStream[F[_]: Async](
    path: String,
    bufferedWriter: OutputStream => F[Unit]
  ): EitherT[F, Throwable, Unit] =
    writer(path)
      .use(bufferedWriter)
      .attemptT

  private[keytool] def parseFileOfTypeOp[F[_]: Async, T](
    parser: String => Option[T]
  )(stream: FileInputStream): F[Option[T]] =
    Resource
      .fromAutoCloseable(Async[F].delay {
        new BufferedReader(new InputStreamReader(stream))
      })
      .use(inStream => Option(inStream.readLine()).flatMap(parser).pure[F])

  private[keytool] def writeTypeToFileStream[F[_]: Async, T](
    serializer: T => String
  )(obj: T)(stream: OutputStream): F[Unit] =
    Resource
      .fromAutoCloseable(Async[F].delay {
        new BufferedWriter(new OutputStreamWriter(stream))
      })
      .use(
        outStream =>
          Async[F].delay {
            outStream.write(serializer(obj))
          }
      )

  private[keytool] def storeKeyPemDecrypted[F[_]: Async](key: Key)(stream: FileOutputStream): F[Unit] =
    Resource
      .fromAutoCloseable(Async[F].delay {
        new PemWriter(new OutputStreamWriter(stream))
      })
      .use { pemWriter =>
        val pemObj = new PemObject("EC KEY", key.getEncoded)
        Async[F].delay {
          pemWriter.writeObject(pemObj)
        }
      }

  private[keytool] def exportPrivateKeyAsHex[F[_]: Async](
    path: String,
    alias: String,
    storePassword: Array[Char],
    keyPassword: Array[Char]
  ): F[String] =
    for {
      keyPair <- keyPairFromStorePath(path, alias, storePassword, keyPassword)
      hex = KeyProvider.privateKeyToHex(keyPair.getPrivate)
      _ <- writer(pathDir(path) + privateKeyHexName)
        .use(
          os =>
            Async[F].delay {
              os.write(hex.getBytes(Charset.forName("UTF-8")))
            }
        )
    } yield hex

  private[keytool] def migrateKeyStoreToSinglePassword[F[_]: Async](
    path: String,
    alias: String,
    storePassword: Array[Char],
    keyPassword: Array[Char],
    distinguishedName: DistinguishedName,
    certificateValidity: Int
  ): F[KeyStore] =
    for {
      keyPair <- keyPairFromStorePath(path, alias, storePassword, keyPassword)
      newKeyStore <- putKeyPairToStorePath(
        keyPair,
        withSinglePasswordSuffix(path),
        alias,
        storePassword,
        storePassword,
        distinguishedName,
        certificateValidity
      )
    } yield newKeyStore

  /**
    * Generates new keypair and puts it to new keyStore at path
    */
  private[keytool] def generateKeyPairToStorePath[F[_]: Async: SecurityProvider](
    path: String,
    alias: String,
    storePassword: Array[Char],
    keyPassword: Array[Char],
    distinguishedName: DistinguishedName,
    certificateValidity: Int
  ): F[KeyStore] =
    writer(withExtension(path))
      .use(
        stream =>
          for {
            keyStore <- createEmptyKeyStore(storePassword)
            keyPair <- KeyProvider.makeKeyPair[F]
            chain <- generateCertificateChain(distinguishedName, certificateValidity, keyPair)
            _ <- setKeyEntry(alias, keyPair, keyPassword, chain)(keyStore)
            _ <- store(stream, storePassword)(keyStore)
          } yield keyStore
      )

  /**
    * Puts existing to new keyStore at path
    */
  private[keytool] def putKeyPairToStorePath[F[_]: Async](
    keyPair: KeyPair,
    path: String,
    alias: String,
    storePassword: Array[Char],
    keyPassword: Array[Char],
    distinguishedName: DistinguishedName,
    certificateValidity: Int
  ): F[KeyStore] =
    writer(withExtension(path))
      .use(
        stream =>
          for {
            keyStore <- createEmptyKeyStore(storePassword)
            chain <- generateCertificateChain(distinguishedName, certificateValidity, keyPair)
            _ <- setKeyEntry(alias, keyPair, keyPassword, chain)(keyStore)
            _ <- store(stream, storePassword)(keyStore)
          } yield keyStore
      )

  private[keytool] def keyPairFromStorePath[F[_]: Async](
    path: String,
    alias: String,
    storepass: Array[Char],
    keypass: Array[Char]
  ): F[KeyPair] =
    reader(path)
      .evalMap(unlockKeyStore[F](storepass))
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

  private def generateCertificateChain[F[_]: Async](
    distinguishedName: DistinguishedName,
    certificateValidity: Int,
    keyPair: KeyPair
  ): F[Array[Certificate]] =
    SelfSignedCertificate
      .generate(distinguishedName.toString, keyPair, certificateValidity, Signing.defaultSignFunc)
      .map(Array(_))

  private def unlockKeyStore[F[_]: Async](
    password: Array[Char]
  )(stream: FileInputStream): F[KeyStore] =
    Async[F].delay { KeyStore.getInstance(storeType) }.flatTap { keyStore =>
      Async[F].delay { keyStore.load(stream, password) }
    }

  private def createEmptyKeyStore[F[_]: Async](password: Array[Char]): F[KeyStore] =
    Async[F].delay { KeyStore.getInstance(storeType) }.flatTap { keyStore =>
      Async[F].delay { keyStore.load(null, password) }
    }

  private def unlockKeyPair[F[_]: Async](alias: String, keyPassword: Array[Char])(keyStore: KeyStore): F[KeyPair] =
    for {
      privateKey <- Async[F].delay { keyStore.getKey(alias, keyPassword).asInstanceOf[PrivateKey] }
      publicKey <- Async[F].delay { keyStore.getCertificate(alias).getPublicKey }
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
