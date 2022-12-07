package zio.flow.runtime

import zio._
import zio.flow.Configuration
import zio.flow.runtime.serialization.ExecutorBinaryCodecs

import java.time.Duration

final case class ExecutionEnvironment(
  codecs: ExecutorBinaryCodecs,
  configuration: Configuration,
  gcPeriod: Duration = 5.minutes
)
