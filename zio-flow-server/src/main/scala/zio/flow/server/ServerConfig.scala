package zio.flow.server

import com.typesafe.config.{ConfigFactory, ConfigObject, ConfigValue, ConfigValueType}
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

  private def isString(value: Config[_]): Boolean =
    value match {
      case Config.Text                    => true
      case Config.LocalDateTime           => true
      case Config.LocalDate               => true
      case Config.LocalTime               => true
      case Config.OffsetDateTime          => true
      case Config.SecretType              => true
      case Config.Fallback(first, second) => isString(first) && isString(second)
      case Config.Lazy(thunk)             => isString(thunk())
      case Config.MapOrFail(inner, _)     => isString(inner)
      case _                              => false
    }

  trait WrappedConfig {
    def getBoolean(path: String): ZIO[Any, Config.Error, Boolean]
    def getNumber(path: String): ZIO[Any, Config.Error, Number]
    def getDuration(path: String): ZIO[Any, Config.Error, Duration]
    def getString(path: String): ZIO[Any, Config.Error, String]
    def getStringList(path: String): ZIO[Any, Config.Error, Chunk[String]]
    def getObjectList(path: String): ZIO[Any, Config.Error, Chunk[WrappedConfig]]
    def getObject(path: String): ZIO[Any, Config.Error, ConfigObject]
    def exists(path: String): Boolean
  }

  object WrappedConfig {
    def fromThrowable(t: Throwable): Config.Error =
      Config.Error.InvalidData(Chunk.empty, t.getMessage)

    def apply(source: com.typesafe.config.Config): WrappedConfig = new WrappedConfig {
      override def getBoolean(path: String): ZIO[Any, Config.Error, Boolean] =
        ZIO.attempt(source.getBoolean(path)).mapError(fromThrowable)

      override def getNumber(path: String): ZIO[Any, Config.Error, Number] =
        ZIO.attempt(source.getNumber(path)).mapError(fromThrowable)

      override def getDuration(path: String): ZIO[Any, Config.Error, Duration] =
        ZIO.attempt(Duration.fromJava(source.getDuration(path))).mapError(fromThrowable)

      override def getString(path: String): ZIO[Any, Config.Error, String] =
        ZIO.attempt(source.getString(path)).mapError(fromThrowable)

      override def getStringList(path: String): ZIO[Any, Config.Error, Chunk[String]] =
        ZIO.attempt(Chunk.fromIterable(source.getStringList(path).asScala)).mapError(fromThrowable)

      override def getObjectList(path: String): ZIO[Any, Config.Error, Chunk[WrappedConfig]] =
        ZIO
          .attempt(
            Chunk.fromIterable(
              source.getObjectList(path).asScala.map(configObject => WrappedConfig(configObject.toConfig))
            )
          )
          .mapError(fromThrowable)

      override def getObject(path: String): ZIO[Any, Config.Error, ConfigObject] =
        ZIO.attempt(source.getObject(path)).mapError(fromThrowable)

      override def exists(path: String): Boolean =
        source.hasPath(path)
    }
    def apply(stringValue: String): WrappedConfig = new WrappedConfig {
      override def getBoolean(path: String): ZIO[Any, Config.Error, Boolean] =
        ZIO.fail(Config.Error.InvalidData(Chunk.empty, "Should not be a string"))

      override def getNumber(path: String): ZIO[Any, Config.Error, Number] =
        ZIO.fail(Config.Error.InvalidData(Chunk.empty, "Should not be a string"))

      override def getDuration(path: String): ZIO[Any, Config.Error, Duration] =
        ZIO.fail(Config.Error.InvalidData(Chunk.empty, "Should not be a string"))

      override def getString(path: String): ZIO[Any, Config.Error, String] =
        if (path.isEmpty) ZIO.succeed(stringValue)
        else ZIO.fail(Config.Error.InvalidData(Chunk.empty, "Should not be a string"))

      override def getStringList(path: String): ZIO[Any, Config.Error, Chunk[String]] =
        ZIO.fail(Config.Error.InvalidData(Chunk.empty, "Should not be a string"))

      override def getObjectList(path: String): ZIO[Any, Config.Error, Chunk[WrappedConfig]] =
        ZIO.fail(Config.Error.InvalidData(Chunk.empty, "Should not be a string"))

      override def getObject(path: String): ZIO[Any, Config.Error, ConfigObject] =
        ZIO.fail(Config.Error.InvalidData(Chunk.empty, "Should not be a string"))

      override def exists(path: String): Boolean =
        path.isEmpty
    }
  }

  // TODO: use zio-config as provider once it is ready
  private def processConfig[A](
    source: WrappedConfig,
    path: String,
    value: Config[A]
  ): ZIO[Any, Config.Error, A] =
    value match {
      case Config.Bool            => source.getBoolean(path)
      case Config.Constant(value) => ZIO.succeed(value)
      case Config.Decimal         => source.getNumber(path).map(number => BigDecimal(number.doubleValue()))
      case Config.Duration        => source.getDuration(path)
      case Config.Fail(message)   => ZIO.fail(Config.Error.InvalidData(Chunk.empty, message))
      case Config.Fallback(left, right) =>
        processConfig(source, path, left).orElse(
          processConfig(source, path, right)
        )
      case Config.Integer             => source.getNumber(path).map(number => BigInt(number.longValue()))
      case Config.Described(inner, _) => processConfig(source, path, inner)
      case Config.Lazy(thunk)         => ZIO.suspendSucceed(processConfig(source, path, thunk()))
      case Config.LocalDateTime       => source.getString(path).map(s => LocalDateTime.parse(s))
      case Config.LocalDate           => source.getString(path).map(s => LocalDate.parse(s))
      case Config.LocalTime           => source.getString(path).map(s => LocalTime.parse(s))
      case Config.MapOrFail(inner, f) =>
        processConfig(source, path, inner).flatMap((x: Any) =>
          ZIO.fromEither(f.asInstanceOf[Any => Either[Config.Error, A]](x))
        )
      case Config.Nested(name, inner) =>
        val innerName = if (path.isEmpty) name else path + "." + name
        if (source.exists(innerName))
          processConfig(source, innerName, inner)
        else
          ZIO.fail(Config.Error.MissingData(Chunk.empty, s"Could not find $innerName"))
      case Config.OffsetDateTime => source.getString(path).map(s => OffsetDateTime.parse(s))
      case Config.SecretType     => source.getString(path).map(Secret(_))
      case Config.Sequence(inner) =>
        if (isString(inner)) {
          source.getStringList(path).flatMap { strs =>
            ZIO.foreach(strs)(str => processConfig(WrappedConfig(str), "", inner))
          }
        } else {
          source.getObjectList(path).flatMap { configs =>
            ZIO.foreach(configs)(config =>
              processConfig(config, "", inner)
                .tapError(err => ZIO.debug(s"item of $path failed: $err"))
            )
          }
        }
      case Config.Table(inner) =>
        source.getObject(path).flatMap { configObject =>
          ZIO
            .foreach(configObject.keySet().asScala.toList) { key =>
              configObject.get(key) match {
                case value: ConfigObject =>
                  processConfig(WrappedConfig(value.toConfig), "", inner).map(value => key -> value)
                case string: ConfigValue if string.valueType() == ConfigValueType.STRING =>
                  processConfig(WrappedConfig(string.unwrapped().toString), "", inner).map(value => key -> value)
                case _ => ZIO.fail(Config.Error.Unsupported(Chunk.empty, "Cannot read primitive table values"))
              }
            }
            .map(_.toMap)
        }
      case Config.Text => source.getString(path)
      case Config.Zipped(left, right, zippable) =>
        processConfig(source, path, left).zip(processConfig(source, path, right)).map { case (a, b) =>
          zippable.zip(a, b).asInstanceOf[A]
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
        processConfig(WrappedConfig(source), "", config)
      }
  }

  def fromTypesafeString(hocon: String): ConfigProvider = new ConfigProvider {
    override def load[A](config: Config[A])(implicit trace: Trace): IO[Config.Error, A] =
      ZIO.attempt {
        ConfigFactory.parseString(hocon).withFallback(ConfigFactory.defaultApplication()).resolve()
      }.mapError(t => Config.Error.SourceUnavailable(Chunk.empty, t.getMessage, Cause.fail(t))).flatMap { source =>
        processConfig(WrappedConfig(source), "", config)
      }
  }
}
