package zio.flow.operation

import zio.json.{JsonCodec, JsonDecoder}
import zio.flow.operation.http.API.NotUnit
import zio.json.internal.{RetractReader, Write}
import zio.schema.Schema

import java.util.UUID
import scala.language.implicitConversions
import java.math.BigDecimal
import java.math.BigInteger

package object http {

  // Paths
  val string: Path[String]   = Path.Match(Schema[String])
  val int: Path[Int]         = Path.Match(Schema[Int])
  val boolean: Path[Boolean] = Path.Match(Schema[Boolean])
  val uuid: Path[UUID]       = Path.Match(Schema[UUID])

  // Query Params
  def string(name: String): Query[String]         = Query.SingleParam(name, Schema[String])
  def boolean(name: String): Query[Boolean]       = Query.SingleParam(name, Schema[Boolean])
  def short(name: String): Query[Short]           = Query.SingleParam(name, Schema[Short])
  def int(name: String): Query[Int]               = Query.SingleParam(name, Schema[Int])
  def long(name: String): Query[Long]             = Query.SingleParam(name, Schema[Long])
  def float(name: String): Query[Float]           = Query.SingleParam(name, Schema[Float])
  def double(name: String): Query[Double]         = Query.SingleParam(name, Schema[Double])
  def char(name: String): Query[Char]             = Query.SingleParam(name, Schema[Char])
  def bigDecimal(name: String): Query[BigDecimal] = Query.SingleParam(name, Schema[BigDecimal])
  def bigInteger(name: String): Query[BigInteger] = Query.SingleParam(name, Schema[BigInteger])
  def uuid(name: String): Query[UUID]             = Query.SingleParam(name, Schema[UUID])

  implicit def stringToPath(string: String): Path[Unit] = Path.path(string)

  // API Ops

  implicit def apiToOps[Input, Output: NotUnit](
    api: API[Input, Output]
  ): APIOps[Input, Output, api.Id] =
    new APIOps(api)

  implicit def apiToOpsUnit[Input](
    api: API[Input, Unit]
  ): APIOpsUnit[Input, api.Id] =
    new APIOpsUnit(api)

  lazy implicit val unitCodec: JsonCodec[Unit] = JsonCodec(
    (_: Unit, _: Option[Int], _: Write) => (),
    (_: List[JsonDecoder.JsonError], _: RetractReader) => ()
  )

}
