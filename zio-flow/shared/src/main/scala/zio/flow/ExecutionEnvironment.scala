package zio.flow

import zio.flow.serialization.{Deserializer, Serializer}

case class ExecutionEnvironment(serializer: Serializer, deserializer: Deserializer)
