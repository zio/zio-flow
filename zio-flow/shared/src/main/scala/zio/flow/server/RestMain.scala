package zio.flow.server

import zhttp.service.Server
import zio.{Scope, ZIO, ZIOAppArgs, ZIOAppDefault}

object RestMain extends ZIOAppDefault {

  val endpoint = WorkflowEndpoint.makeBroken()

  val run: ZIO[Any with ZIOAppArgs with Scope, Throwable, Nothing] =
    Server.start(8090, endpoint.endpoint)
}
