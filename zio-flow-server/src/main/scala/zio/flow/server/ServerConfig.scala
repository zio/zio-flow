package zio.flow.server

import com.typesafe.config.{ConfigFactory, ConfigObject}
import zio.Config.Secret
import zio.flow.server.ServerConfig._
import zio.metrics.connectors.MetricsConfig
import zio.{Cause, Chunk, Config, ConfigProvider, Duration, IO, Trace, ZIO}

import java.nio.file.Path
import java.time.{LocalDate, LocalDateTime, LocalTime, OffsetDateTime}
import scala.jdk.CollectionConverters._

final case class ServerConfig(
  port: Int,
  keyValueStore: BackendImplementation,
  indexedStore: BackendImplementation,
  metrics: MetricsConfig,
  serializationFormat: SerializationFormat,
  gcPeriod: Duration
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
    Config.duration("interval").map(MetricsConfig.apply)

  val config: Config[ServerConfig] =
    (
      Config.int("port") ++
        BackendImplementation.config.nested("key-value-store") ++
        BackendImplementation.config.nested("indexed-store") ++
        metricsConfig.nested("metrics") ++
        SerializationFormat.config.nested("serialization-format") ++
        Config.duration("gc-period")
    ).map { case (port, kvStore, ixStore, metrics, ser, gcPeriod) =>
      ServerConfig(port, kvStore, ixStore, metrics, ser, gcPeriod)
    }

  // TODO: use zio-config as provider once it is ready
  private def processConfig[A](
    source: com.typesafe.config.Config,
    path: String,
    value: Config[A]
  ): ZIO[Any, Config.Error, A] = {
    def fromThrowable(t: Throwable): Config.Error =
      Config.Error.InvalidData(Chunk.empty, t.getMessage)

    value match {
      case Config.Bool                  => ZIO.attempt(source.getBoolean(path)).mapError(fromThrowable)
      case Config.Constant(value)       => ZIO.succeed(value)
      case Config.Decimal               => ZIO.attempt(BigDecimal(source.getNumber(path).doubleValue())).mapError(fromThrowable)
      case Config.Duration              => ZIO.attempt(Duration.fromJava(source.getDuration(path))).mapError(fromThrowable)
      case Config.Fail(message)         => ZIO.fail(Config.Error.InvalidData(Chunk.empty, message))
      case Config.Fallback(left, right) => processConfig(source, path, left).orElse(processConfig(source, path, right))
      case Config.Integer               => ZIO.attempt(BigInt(source.getNumber(path).longValue())).mapError(fromThrowable)
      case Config.Described(inner, _)   => processConfig(source, path, inner)
      case Config.Lazy(thunk)           => ZIO.suspendSucceed(processConfig(source, path, thunk()))
      case Config.LocalDateTime         => ZIO.attempt(LocalDateTime.parse(source.getString(path))).mapError(fromThrowable)
      case Config.LocalDate             => ZIO.attempt(LocalDate.parse(source.getString(path))).mapError(fromThrowable)
      case Config.LocalTime             => ZIO.attempt(LocalTime.parse(source.getString(path))).mapError(fromThrowable)
      case Config.MapOrFail(inner, f) =>
        processConfig(source, path, inner).flatMap((x: Any) =>
          ZIO.fromEither(f.asInstanceOf[Any => Either[Config.Error, A]](x))
        )
      case Config.Nested(name, inner) => processConfig(source, if (path.isEmpty) name else path + "." + name, inner)
      case Config.OffsetDateTime      => ZIO.attempt(OffsetDateTime.parse(source.getString(path))).mapError(fromThrowable)
      case Config.SecretType          => ZIO.attempt(Secret(source.getString(path))).mapError(fromThrowable)
      case Config.Sequence(inner) =>
        ZIO.attempt(source.getObjectList(path)).mapError(fromThrowable).flatMap {
          configList => // TODO: support for list of primitives
            ZIO.foreach(Chunk.fromIterable(configList.asScala)) { item =>
              processConfig(item.toConfig, "", inner)
            }
        }
      case Config.Table(inner) =>
        ZIO.attempt(source.getObject(path)).mapError(fromThrowable).flatMap { configObject =>
          ZIO
            .foreach(configObject.keySet().asScala.toList) { key =>
              configObject.get(key) match {
                case value: ConfigObject => processConfig(value.toConfig, "", inner).map(value => key -> value)
                case _                   => ZIO.fail(Config.Error.Unsupported(Chunk.empty, "Cannot read primitive table values"))
              }
            }
            .map(_.toMap)
        }
      case Config.Text => ZIO.attempt(source.getString(path)).mapError(fromThrowable)
      case Config.Zipped(left, right, zippable) =>
        processConfig(source, path, left).zip(processConfig(source, path, right)).map { case (a, b) =>
          zippable.zip(a, b).asInstanceOf[A]
        }
    }
  }

  /** Temporary bridge between zio-config and ZIO's core config API */
  def fromTypesafe(externalFile: Option[Path]): ConfigProvider = new ConfigProvider {
    override def load[A](config: Config[A])(implicit trace: Trace): IO[Config.Error, A] =
      ZIO.attempt {
        externalFile match {
          case Some(path) =>
            ConfigFactory.parseFile(path.toFile).withFallback(ConfigFactory.defaultApplication()).resolve()
          case None => ConfigFactory.load()
        }
      }.mapError(t => Config.Error.SourceUnavailable(Chunk.empty, t.getMessage, Cause.fail(t))).flatMap { source =>
        processConfig(source, "", config)
      }
  }
}
