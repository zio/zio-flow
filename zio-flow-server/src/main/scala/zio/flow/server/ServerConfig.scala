package zio.flow.server

import zio.aws.core.config.CommonAwsConfig
import zio.aws.core.config.descriptors.commonAwsConfig
import zio.aws.netty.NettyClientConfig
import zio.aws.netty.descriptors.nettyClientConfig
import zio.flow.runtime.PersisterConfig
import zio.flow.server.ServerConfig._
import zio.metrics.connectors.MetricsConfig
import zio.{Chunk, Config, Duration, LogLevel, durationInt}

final case class ServerConfig(
  keyValueStore: BackendImplementation,
  indexedStore: BackendImplementation,
  metrics: MetricsConfig,
  serializationFormat: SerializationFormat,
  gcPeriod: Duration,
  logLevel: LogLevel,
  commonAwsConfig: CommonAwsConfig,
  awsNettyClientConfig: NettyClientConfig,
  persisterConfig: PersisterConfig
)

object ServerConfig {

  sealed trait BackendImplementation
  object BackendImplementation {
    case object InMemory  extends BackendImplementation
    case object RocksDb   extends BackendImplementation
    case object Cassandra extends BackendImplementation
    case object DynamoDb  extends BackendImplementation

    val config: Config[BackendImplementation] = Config.string.mapOrFail {
      case "in-memory" => Right(InMemory)
      case "rocksdb"   => Right(RocksDb)
      case "cassandra" => Right(Cassandra)
      case "dynamodb"  => Right(DynamoDb)
      case other       => Left(Config.Error.InvalidData(Chunk.empty, s"Unknown backend implementation: $other"))
    }
  }

  sealed trait SerializationFormat
  object SerializationFormat {
    case object Json     extends SerializationFormat
    case object Protobuf extends SerializationFormat

    val config: Config[SerializationFormat] = Config.string.mapOrFail {
      case "json"     => Right(Json)
      case "protobuf" => Right(Protobuf)
      case other      => Left(Config.Error.InvalidData(Chunk.empty, s"Unknown serialization format: $other"))
    }
  }

  private val metricsConfig: Config[MetricsConfig] =
    Config.duration("interval").map(MetricsConfig(_))

  private val logLevelConfig: Config[LogLevel] =
    Config.string.mapOrFail {
      case "trace"   => Right(LogLevel.Trace)
      case "debug"   => Right(LogLevel.Debug)
      case "info"    => Right(LogLevel.Info)
      case "warning" => Right(LogLevel.Warning)
      case "error"   => Right(LogLevel.Error)
      case "fatal"   => Right(LogLevel.Fatal)
      case other     => Left(Config.Error.InvalidData(Chunk.empty, s"Unknown log level: $other"))
    }

  val config: Config[ServerConfig] =
    (
      BackendImplementation.config.nested("key-value-store") ++
        BackendImplementation.config.nested("indexed-store") ++
        metricsConfig.nested("metrics") ++
        SerializationFormat.config.nested("serialization-format") ++
        Config.duration("gc-period") ++
        logLevelConfig.nested("log-level").withDefault(LogLevel.Info) ++
        commonAwsConfig.nested("aws") ++
        nettyClientConfig.nested("aws-netty") ++
        PersisterConfig.config
          .nested("persister")
          .withDefault(PersisterConfig.PeriodicSnapshots(afterEvery = Some(100), afterDuration = Some(1.minute)))
    ).map { case (kvStore, ixStore, metrics, ser, gcPeriod, logLevel, aws, awsNetty, persisterConfig) =>
      ServerConfig(kvStore, ixStore, metrics, ser, gcPeriod, logLevel, aws, awsNetty, persisterConfig)
    }
}
