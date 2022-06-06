package zio.flow.examples

import zio.flow._
import zio.schema.Schema
import zio.flow.operation.http.API
import zio.flow.operation.http._

import zio.json.JsonCodec

object HttpFlowExample {

  case class UserData(name: String)

  implicit val userDataSchema: Schema[UserData]       = ???
  implicit val userDataJsonCodec: JsonCodec[UserData] = ???

  val setUserData = Activity[(Int, UserData), Unit](
    "setUserData",
    "set user data",
    Operation.Http(
      "https://example.com",
      API.post("users" / int).input[UserData]
    ),
    check = ???,
    compensate = ZFlow.succeed(())
  )

  val flow = for {
    id   <- ZFlow.succeed(1)
    data <- ZFlow.succeed(UserData("Albert"))
    _    <- setUserData(id, data)
  } yield ()

}
