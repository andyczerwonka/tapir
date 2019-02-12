package tapir

import java.io.{File, InputStream}
import java.nio.ByteBuffer
import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file.Path

import tapir.DecodeResult._
import tapir.generic.{FormCodecDerivation, MultipartCodecDerivation}
import tapir.internal.UrlencodedData
import tapir.model.Part

/**
  * A pair of functions, one to encode a value of type `T` to a raw value of type `R`,
  * and another one to decode.
  *
  * Also contains meta-data on the `schema` of the value, the media type and the raw value type.
  *
  * @tparam T Type of the values which can be encoded / to which raw values can be decoded.
  * @tparam M The media type of encoded values.
  * @tparam R Type of the raw value to which values are encoded.
  */
trait Codec[T, M <: MediaType, R] { outer =>
  def encode(t: T): R
  def decode(s: R): DecodeResult[T]
  def meta: CodecMeta[M, R]

  def mapDecode[TT](f: T => DecodeResult[TT])(g: TT => T): Codec[TT, M, R] =
    new Codec[TT, M, R] {
      override def encode(t: TT): R = outer.encode(g(t))
      override def decode(s: R): DecodeResult[TT] = outer.decode(s).flatMap(f)
      override val meta: CodecMeta[M, R] = outer.meta
    }

  def map[TT](f: T => TT)(g: TT => T): Codec[TT, M, R] = mapDecode[TT](f.andThen(Value.apply))(g)

  private def withMeta[M2 <: MediaType](meta2: CodecMeta[M2, R]): Codec[T, M2, R] = new Codec[T, M2, R] {
    override def encode(t: T): R = outer.encode(t)
    override def decode(s: R): DecodeResult[T] = outer.decode(s)
    override val meta: CodecMeta[M2, R] = meta2
  }

  def mediaType[M2 <: MediaType](m2: M2): Codec[T, M2, R] = withMeta[M2](meta.copy[M2, R](mediaType = m2))
  def schema(s2: Schema): Codec[T, M, R] = withMeta(meta.copy(schema = s2))
}

object Codec extends FormCodecDerivation with MultipartCodecDerivation {
  type PlainCodec[T] = Codec[T, MediaType.TextPlain, String]
  type JsonCodec[T] = Codec[T, MediaType.Json, String]

  implicit val stringPlainCodecUtf8: PlainCodec[String] = stringCodec(StandardCharsets.UTF_8)
  implicit val shortPlainCodec: PlainCodec[Short] = plainCodec[Short](_.toShort, Schema.SInteger)
  implicit val intPlainCodec: PlainCodec[Int] = plainCodec[Int](_.toInt, Schema.SInteger)
  implicit val longPlainCodec: PlainCodec[Long] = plainCodec[Long](_.toLong, Schema.SInteger)
  implicit val floatPlainCodec: PlainCodec[Float] = plainCodec[Float](_.toFloat, Schema.SNumber)
  implicit val doublePlainCodec: PlainCodec[Double] = plainCodec[Double](_.toDouble, Schema.SNumber)
  implicit val booleanPlainCodec: PlainCodec[Boolean] = plainCodec[Boolean](_.toBoolean, Schema.SBoolean)

  def stringCodec(charset: Charset): PlainCodec[String] = plainCodec(identity, Schema.SString, charset)

  private def plainCodec[T](parse: String => T, _schema: Schema, charset: Charset = StandardCharsets.UTF_8): PlainCodec[T] =
    new PlainCodec[T] {
      override def encode(t: T): String = t.toString
      override def decode(s: String): DecodeResult[T] =
        try Value(parse(s))
        catch {
          case e: Exception => Error(s, e)
        }
      override val meta: CodecMeta[MediaType.TextPlain, String] = CodecMeta(_schema, MediaType.TextPlain(charset), StringValueType(charset))
    }

  implicit val byteArrayCodec: Codec[Array[Byte], MediaType.OctetStream, Array[Byte]] = binaryCodec(ByteArrayValueType)
  implicit val byteBufferCodec: Codec[ByteBuffer, MediaType.OctetStream, ByteBuffer] = binaryCodec(ByteBufferValueType)
  implicit val inputStreamCodec: Codec[InputStream, MediaType.OctetStream, InputStream] = binaryCodec(InputStreamValueType)
  implicit val fileCodec: Codec[File, MediaType.OctetStream, File] = binaryCodec(FileValueType)
  implicit val pathCodec: Codec[Path, MediaType.OctetStream, File] = binaryCodec(FileValueType).map(_.toPath)(_.toFile)

  def binaryCodec[T](_rawValueType: RawValueType[T]): Codec[T, MediaType.OctetStream, T] = new Codec[T, MediaType.OctetStream, T] {
    override def encode(b: T): T = b
    override def decode(b: T): DecodeResult[T] = Value(b)
    override val meta: CodecMeta[MediaType.OctetStream, T] = CodecMeta(Schema.SBinary, MediaType.OctetStream(), _rawValueType)
  }

  implicit val formSeqCodecUtf8: Codec[Seq[(String, String)], MediaType.XWwwFormUrlencoded, String] = formSeqCodec(StandardCharsets.UTF_8)
  implicit val formMapCodecUtf8: Codec[Map[String, String], MediaType.XWwwFormUrlencoded, String] = formMapCodec(StandardCharsets.UTF_8)

  def formSeqCodec(charset: Charset): Codec[Seq[(String, String)], MediaType.XWwwFormUrlencoded, String] =
    stringCodec(charset).map(UrlencodedData.decode(_, charset))(UrlencodedData.encode(_, charset)).mediaType(MediaType.XWwwFormUrlencoded())
  def formMapCodec(charset: Charset): Codec[Map[String, String], MediaType.XWwwFormUrlencoded, String] =
    formSeqCodec(charset).map(_.toMap)(_.toSeq)

  implicit val multipartFormSeqCodec: Codec[Seq[AnyPart], MediaType.MultipartFormData, Seq[RawPart]] =
    multipartCodec(Map.empty, defaultCodec = Some(CodecForMany.fromCodec(byteArrayCodec)))

  /**
    * @param partCodecs For each supported part, a codec which encodes the part value into a raw value. A single part
    *                   value might be encoded as multiple (or none) raw values.
    * @param defaultCodec Default codec to use for parts which are not defined in `partCodecs`. `None`, if extra parts
    *                     should be discarded.
    */
  def multipartCodec(partCodecs: Map[String, AnyCodecForMany],
                     defaultCodec: Option[AnyCodecForMany]): Codec[Seq[AnyPart], MediaType.MultipartFormData, Seq[RawPart]] =
    new Codec[Seq[AnyPart], MediaType.MultipartFormData, Seq[RawPart]] {
      private val mvt = MultipartValueType(partCodecs, defaultCodec)

      override def encode(t: Seq[AnyPart]): Seq[RawPart] = {
        t.flatMap { part =>
          mvt.partCodec(part.name).toList.flatMap { codec =>
            // a single value-part might yield multiple raw-parts (e.g. for repeated fields)
            val rawParts: List[RawPart] = codec.asInstanceOf[CodecForMany[Any, _, _]].encode(part.body).map { b =>
              part.copy(body = b)
            }

            rawParts
          }
        }
      }
      override def decode(r: Seq[RawPart]): DecodeResult[Seq[AnyPart]] = {
        val rawPartsByName = r.groupBy(_.name)

        // we need to decode all parts for which there's a codec defined (even if that part is missing a value -
        // it might still decode to e.g. None), and if there's a default codec also the extra parts
        val partNamesToDecode = partCodecs.keys.toSet ++ (if (defaultCodec.isDefined) rawPartsByName.keys.toSet else Set.empty)

        // there might be multiple raw-parts for each name, yielding a single value-part
        val anyParts: List[DecodeResult[AnyPart]] = partNamesToDecode.map { name =>
          val codec = mvt.partCodec(name).get
          val rawParts = rawPartsByName.get(name).toList.flatten
          codec.asInstanceOf[CodecForMany[_, _, Any]].decode(rawParts.map(_.body)).map { body =>
            // we know there's at least one part. Using this part to create the value-part
            rawParts.headOption match {
              case Some(rawPart) => rawPart.copy(body = body)
              case None          => Part(name, body)
            }
          }
        }.toList

        DecodeResult.sequence(anyParts)
      }
      override val meta: CodecMeta[MediaType.MultipartFormData, Seq[RawPart]] =
        CodecMeta(Schema.SBinary, MediaType.MultipartFormData(), mvt)
    }
}

/**
  * A codec which can encode to optional raw values / decode from optional raw values.
  * An optional raw value specifies if the raw value should be included in the output, or not.
  * Depending on the codec, decoding from an optional value might yield [[DecodeResult.Missing]].
  *
  * Should be used for inputs/outputs which allow optional values.
  */
trait CodecForOptional[T, M <: MediaType, R] { outer =>
  def encode(t: T): Option[R]
  def decode(s: Option[R]): DecodeResult[T]
  def meta: CodecMeta[M, R]

  def mapDecode[TT](f: T => DecodeResult[TT])(g: TT => T): CodecForOptional[TT, M, R] =
    new CodecForOptional[TT, M, R] {
      override def encode(t: TT): Option[R] = outer.encode(g(t))
      override def decode(s: Option[R]): DecodeResult[TT] = outer.decode(s).flatMap(f)
      override val meta: CodecMeta[M, R] = outer.meta
    }

  def map[TT](f: T => TT)(g: TT => T): CodecForOptional[TT, M, R] = mapDecode[TT](f.andThen(Value.apply))(g)
}

object CodecForOptional {
  implicit def fromCodec[T, M <: MediaType, R](implicit c: Codec[T, M, R]): CodecForOptional[T, M, R] = new CodecForOptional[T, M, R] {
    override def encode(t: T): Option[R] = Some(c.encode(t))
    override def decode(s: Option[R]): DecodeResult[T] = s match {
      case None    => DecodeResult.Missing
      case Some(h) => c.decode(h)
    }
    override val meta: CodecMeta[M, R] = c.meta
  }

  implicit def forOption[T, M <: MediaType, R](implicit tm: Codec[T, M, R]): CodecForOptional[Option[T], M, R] =
    new CodecForOptional[Option[T], M, R] {
      override def encode(t: Option[T]): Option[R] = t.map(v => tm.encode(v))
      override def decode(s: Option[R]): DecodeResult[Option[T]] = s match {
        case None     => DecodeResult.Value(None)
        case Some(ss) => tm.decode(ss).map(Some(_))
      }
      override val meta: CodecMeta[M, R] = tm.meta.copy(isOptional = true)
    }
}

/**
  * A codec which can encode to multiple (0..n) raw values / decode from multiple raw values.
  * An multiple raw value specifies that the raw values should be included in the output multiple times.
  * Depending on the codec, decoding from a multiple value might yield [[DecodeResult.Missing]] or [[DecodeResult.Multiple]].
  *
  * Should be used for inputs/outputs which allow multiple values.
  */
trait CodecForMany[T, M <: MediaType, R] { outer =>
  def encode(t: T): List[R]
  def decode(s: List[R]): DecodeResult[T]
  def meta: CodecMeta[M, R]

  def mapDecode[TT](f: T => DecodeResult[TT])(g: TT => T): CodecForMany[TT, M, R] =
    new CodecForMany[TT, M, R] {
      override def encode(t: TT): List[R] = outer.encode(g(t))
      override def decode(s: List[R]): DecodeResult[TT] = outer.decode(s).flatMap(f)
      override val meta: CodecMeta[M, R] = outer.meta
    }

  def map[TT](f: T => TT)(g: TT => T): CodecForMany[TT, M, R] = mapDecode[TT](f.andThen(Value.apply))(g)
}

object CodecForMany {
  type PlainCodecForMany[T] = CodecForMany[T, MediaType.TextPlain, String]

  implicit def fromCodec[T, M <: MediaType, R](implicit c: Codec[T, M, R]): CodecForMany[T, M, R] = new CodecForMany[T, M, R] {
    override def encode(t: T): List[R] = List(c.encode(t))
    override def decode(s: List[R]): DecodeResult[T] = s match {
      case Nil     => DecodeResult.Missing
      case List(h) => c.decode(h)
      case l       => DecodeResult.Multiple(l)
    }
    override val meta: CodecMeta[M, R] = c.meta
  }

  implicit def forOption[T, M <: MediaType, R](implicit tm: Codec[T, M, R]): CodecForMany[Option[T], M, R] =
    new CodecForMany[Option[T], M, R] {
      override def encode(t: Option[T]): List[R] = t.map(v => tm.encode(v)).toList
      override def decode(s: List[R]): DecodeResult[Option[T]] = s match {
        case Nil     => DecodeResult.Value(None)
        case List(h) => tm.decode(h).map(Some(_))
        case l       => DecodeResult.Multiple(l)
      }
      override val meta: CodecMeta[M, R] = tm.meta.copy(isOptional = true)
    }

  implicit def forList[T, M <: MediaType, R](implicit tm: Codec[T, M, R]): CodecForMany[List[T], M, R] =
    new CodecForMany[List[T], M, R] {
      override def encode(t: List[T]): List[R] = t.map(v => tm.encode(v))
      override def decode(s: List[R]): DecodeResult[List[T]] = DecodeResult.sequence(s.map(tm.decode))
      override val meta: CodecMeta[M, R] = tm.meta.copy(isOptional = true, schema = Schema.SArray(tm.meta.schema))
    }
}

case class CodecMeta[M <: MediaType, R] private (isOptional: Boolean, schema: Schema, mediaType: M, rawValueType: RawValueType[R])
object CodecMeta {
  def apply[M <: MediaType, R](schema: Schema, mediaType: M, rawValueType: RawValueType[R]): CodecMeta[M, R] =
    CodecMeta(isOptional = false, schema, mediaType, rawValueType)
}

sealed trait RawValueType[R]
case class StringValueType(charset: Charset) extends RawValueType[String]
case object ByteArrayValueType extends RawValueType[Array[Byte]]
case object ByteBufferValueType extends RawValueType[ByteBuffer]
case object InputStreamValueType extends RawValueType[InputStream]
case object FileValueType extends RawValueType[File]
case class MultipartValueType(partCodecs: Map[String, AnyCodecForMany], defaultCodec: Option[AnyCodecForMany])
    extends RawValueType[Seq[RawPart]] {
  private[tapir] def partCodec(name: String): Option[AnyCodecForMany] = partCodecs.get(name).orElse(defaultCodec)
  def partCodecMeta(name: String): Option[AnyCodecMeta] = partCodec(name).map(_.meta)
}
