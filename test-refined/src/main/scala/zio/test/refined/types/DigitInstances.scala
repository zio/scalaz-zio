package zio.test.refined.types

import eu.timepit.refined.api.Refined
import eu.timepit.refined.types.all._
import zio.test.Gen
import zio.test.magnolia.DeriveGen

object digit extends DigitInstances

trait DigitInstances {
  private def hexStringsGen(n: Int): Gen[Any, String] = Gen
    .oneOf(
      Gen.stringN(n)(Gen.hexCharUpper),
      Gen.stringN(n)(Gen.hexCharLower)
    )

  val md5Gen: Gen[Any, MD5]       = hexStringsGen(32).map(Refined.unsafeApply)
  val sha1Gen: Gen[Any, SHA1]     = hexStringsGen(40).map(Refined.unsafeApply)
  val sha224Gen: Gen[Any, SHA224] = hexStringsGen(56).map(Refined.unsafeApply)
  val sha256Gen: Gen[Any, SHA256] = hexStringsGen(64).map(Refined.unsafeApply)
  val sha384Gen: Gen[Any, SHA384] = hexStringsGen(96).map(Refined.unsafeApply)
  val sha512Gen: Gen[Any, SHA512] = hexStringsGen(128).map(Refined.unsafeApply)

  implicit val md5Arbitrary: DeriveGen[MD5]       = DeriveGen.instance(md5Gen)
  implicit val sha1Arbitrary: DeriveGen[SHA1]     = DeriveGen.instance(sha1Gen)
  implicit val sha224Arbitrary: DeriveGen[SHA224] = DeriveGen.instance(sha224Gen)
  implicit val sha256Arbitrary: DeriveGen[SHA256] = DeriveGen.instance(sha256Gen)
  implicit val sha384Arbitrary: DeriveGen[SHA384] = DeriveGen.instance(sha384Gen)
  implicit val sha512Arbitrary: DeriveGen[SHA512] = DeriveGen.instance(sha512Gen)
}
