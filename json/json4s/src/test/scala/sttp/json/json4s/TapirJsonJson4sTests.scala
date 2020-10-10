package sttp.tapir.json.json4s

import org.scalatest.Assertion
import sttp.tapir._
import sttp.tapir.DecodeResult._

import java.util.Date
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object TapirJsonJson4sCodec extends TapirJsonJson4s

class TapirJsonJson4sTests extends AnyFlatSpec with Matchers {
  case class Customer(name: String, yearOfBirth: Int, lastPurchase: Option[Long])

  object Customer {
    implicit val rw: Format[Customer] = Json.format
  }

  val customerDecoder = TapirJsonPlayCodec.readsWritesCodec[Customer]

  def testEncodeDecode[T: Manifest: Schema: Validator](original: T): Assertion = {
    val codec = TapirJsonPlayCodec.readsWritesCodec[T]

    val encoded = codec.encode(original)
    codec.decode(encoded) match {
      case Value(d)                => d shouldBe original
      case f: DecodeResult.Failure => fail(f.toString)
    }
  }

}
