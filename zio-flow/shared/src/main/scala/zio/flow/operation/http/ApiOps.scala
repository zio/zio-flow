package zio.flow.operation.http

import zio.ZIO
import zhttp.service.EventLoopGroup
import zhttp.service.ChannelFactory
import zio.schema.codec.JsonCodec

final class APIOps[Input, Output: API.NotUnit, Id](
  val self: API.WithId[Input, Output, Id]
) {
  def call(host: String)(params: Input): ZIO[EventLoopGroup with ChannelFactory, Throwable, Output] =
    ClientInterpreter.interpret(host)(self)(params).flatMap(_.body).flatMap { string =>
      JsonCodec.decode(self.outputSchema)(string) match {
        case Left(err)    => ZIO.fail(new Error(s"Could not parse response: $err"))
        case Right(value) => ZIO.succeed(value)
      }
    }
}

final class APIOpsUnit[Input, Id](val self: API.WithId[Input, Unit, Id]) {
  def call(host: String)(params: Input): ZIO[EventLoopGroup with ChannelFactory, Throwable, Unit] =
    ClientInterpreter.interpret(host)(self)(params).unit
}
