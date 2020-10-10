package sttp.tapir.json.json4s

import org.json4s.Extraction._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import sttp.tapir.Codec.JsonCodec
import sttp.tapir.DecodeResult.{Error, Value}
import sttp.tapir.SchemaType._
import sttp.tapir._

import scala.util.{Failure, Success, Try}

trait TapirJsonJson4s {

  implicit val formats: Formats

  def jsonBody[T: Manifest: Schema: Validator]: EndpointIO.Body[String, T] = anyFromUtf8StringBody(readsWritesCodec[T])

  implicit def readsWritesCodec[T: Manifest: Schema: Validator]: JsonCodec[T] =
    Codec.json { jsonString =>
      Try(extract[T](parse(jsonString))) match {
        case Success(value) => Value(value)
        case Failure(ex)    => Error("Json4s decoder failed", ex)
      }
    } { t =>
      compact(render(decompose(t)))
    }

  implicit val schemaForJson4sObject: Schema[JValue] =
    Schema(
      SProduct(
        SObjectInfo("org.json4s.JsonAST.JValue"),
        List.empty
      )
    )

  implicit val validatorForJson4sValue: Validator[JValue] = Validator.pass

}
