package zio.flow

import zio.schema.DynamicValue
import zio.stm.{TMap, ZSTM}
import zio.{EnvironmentTag, Scope, UIO, ZIO, ZLayer}

import java.util.UUID

trait LocalContext {
  def pushVariable(name: LocalVariableName, value: DynamicValue): UIO[Unit]
  def popVariable(name: LocalVariableName): UIO[Unit]
  def getVariable(name: LocalVariableName): UIO[Option[DynamicValue]]

  def scopedVariable(name: LocalVariableName, value: DynamicValue): ZIO[Scope, Nothing, Unit] =
    ZIO.acquireRelease(pushVariable(name, value))(_ => popVariable(name))

  def withVariable[R, E, A](name: LocalVariableName, value: DynamicValue)(f: ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.scoped[R](scopedVariable(name, value) *> f)

}

object LocalContext {
  def generateFreshVariableName: LocalVariableName =
    LocalVariableName(UUID.randomUUID())

  def pushVariable(name: LocalVariableName, value: DynamicValue): ZIO[LocalContext, Nothing, Unit] =
    ZIO.serviceWithZIO(_.pushVariable(name, value))

  def popVariable(name: LocalVariableName): ZIO[LocalContext, Nothing, Unit] =
    ZIO.serviceWithZIO(_.popVariable(name))

  def getVariable(name: LocalVariableName): ZIO[LocalContext, Nothing, Option[DynamicValue]] =
    ZIO.serviceWithZIO(_.getVariable(name))

  def scopedVariable(name: LocalVariableName, value: DynamicValue): ZIO[LocalContext with Scope, Nothing, Unit] =
    ZIO.serviceWithZIO[LocalContext](_.scopedVariable(name, value))

  def withVariable[R: EnvironmentTag, E, A](name: LocalVariableName, value: DynamicValue)(
    f: ZIO[R, E, A]
  ): ZIO[LocalContext with R, E, A] =
    ZIO.serviceWithZIO[LocalContext](_.withVariable(name, value)(f))

  private final case class InMemory(
    store: TMap[LocalVariableName, List[DynamicValue]]
  ) extends LocalContext {

    override def pushVariable(name: LocalVariableName, value: DynamicValue): UIO[Unit] =
      store
        .get(name)
        .flatMap {
          case None =>
            store.put(name, List(value))
          case Some(values) =>
            store.put(name, value :: values)
        }
        .commit

    override def popVariable(name: LocalVariableName): UIO[Unit] =
      store
        .get(name)
        .flatMap {
          case Some(_ :: Nil) | Some(Nil) =>
            store.delete(name)
          case Some(_ :: rest) =>
            store.put(name, rest)
          case None => ZSTM.unit
        }
        .commit

    override def getVariable(name: LocalVariableName): UIO[Option[DynamicValue]] =
      store
        .get(name)
        .map(_.flatMap(_.headOption))
        .commit
  }

  def inMemory: ZLayer[Any, Nothing, LocalContext] =
    ZLayer {
      for {
        vars <- TMap.empty[LocalVariableName, List[DynamicValue]].commit
      } yield InMemory(vars)
    }

}
