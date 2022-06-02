package zio.flow.operation

import zhttp.service.{ChannelFactory, EventLoopGroup}
import zio.json.{JsonCodec, JsonDecoder}
import zio.flow.operation.http.API.NotUnit
import zio.json.internal.{RetractReader, Write}
import zio.schema.Schema

import java.util.UUID
import scala.language.implicitConversions
import zio.ZIO

package object http {

  // Paths
  val string: Path[String]   = Path.Match("string", Schema[String])
  val int: Path[Int]         = Path.Match("int", Schema[Int])
  val boolean: Path[Boolean] = Path.Match("boolean", Schema[Boolean])
  val uuid: Path[UUID]       = Path.Match("uuid", Schema[UUID])

  // Query Params
  def string(name: String): Query[String]   = Query.SingleParam(name, Schema[String])
  def int(name: String): Query[Int]         = Query.SingleParam(name, Schema[Int])
  def boolean(name: String): Query[Boolean] = Query.SingleParam(name, Schema[Boolean])

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

  final class APIOps[Input, Output: NotUnit, Id](
      val self: API.WithId[Input, Output, Id]
  ){
    def call(host: String)(params: Input): ZIO[EventLoopGroup with ChannelFactory, Throwable, Output] = {
      ClientInterpreter.interpret(host)(self)(params).flatMap(_.bodyAsString).flatMap { string =>
        self.outputCodec.decodeJson(string) match {
          case Left(err)    => ZIO.fail(new Error(s"Could not parse response: $err"))
          case Right(value) => ZIO.succeed(value)
        }
      }
    }
  }

  final class APIOpsUnit[Input, Id](val self: API.WithId[Input, Unit, Id]){
    def call(host: String)(params: Input): ZIO[EventLoopGroup with ChannelFactory, Throwable, Unit] = {
      ClientInterpreter.interpret(host)(self)(params).unit
    }
  }

  lazy implicit val unitCodec: JsonCodec[Unit] = JsonCodec(
    (_: Unit, _: Option[Int], _: Write) => (),
    (_: List[JsonDecoder.JsonError], _: RetractReader) => ()
  )

}
