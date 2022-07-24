package zio.flow

import zio.stm.{TMap, TSet, ZSTM}
import zio.{ZIO, ZLayer}

import java.util.UUID

trait LocalContext {
  def pushBinding(unbound: BindingName, variable: Remote.Variable[_]): ZIO[Any, Nothing, Unit]
  def popBinding(unbound: BindingName): ZIO[Any, Nothing, Unit]
  def getBinding(unbound: BindingName): ZIO[Any, Nothing, Option[Remote.Variable[_]]]
  def getAllVariables: ZIO[Any, Nothing, Set[Remote.Variable[_]]]
}

object LocalContext {
  def generateFreshBinding: BindingName =
    BindingName(UUID.randomUUID())

  def pushBinding(unbound: BindingName, variable: Remote.Variable[_]): ZIO[LocalContext, Nothing, Unit] =
    ZIO.serviceWithZIO(_.pushBinding(unbound, variable))
  def popBinding(unbound: BindingName): ZIO[LocalContext, Nothing, Unit] =
    ZIO.serviceWithZIO(_.popBinding(unbound))
  def getBinding(unbound: BindingName): ZIO[LocalContext, Nothing, Option[Remote.Variable[_]]] =
    ZIO.serviceWithZIO(_.getBinding(unbound))
  def getAllVariables: ZIO[LocalContext, Nothing, Set[Remote.Variable[_]]] =
    ZIO.serviceWithZIO(_.getAllVariables)

  private final case class InMemory(
    store: TMap[BindingName, List[Remote.Variable[_]]],
    all: TSet[Remote.Variable[_]]
  ) extends LocalContext {
    override def pushBinding(unbound: BindingName, variable: Remote.Variable[_]): ZIO[Any, Nothing, Unit] =
      (store
        .get(unbound)
        .flatMap {
          case None =>
            store.put(unbound, List(variable))
          case Some(list) =>
            store.put(unbound, variable :: list)
        }
        .zipRight(all.put(variable)))
        .commit

    override def popBinding(unbound: BindingName): ZIO[Any, Nothing, Unit] =
      (store
        .get(unbound)
        .flatMap {
          case None            => ZSTM.unit
          case Some(_ :: rest) => store.put(unbound, rest)
          case Some(_)         => store.delete(unbound)
        })
        .commit

    override def getBinding(unbound: BindingName): ZIO[Any, Nothing, Option[Remote.Variable[_]]] =
      (store
        .get(unbound)
        .flatMap {
          case Some(head :: _) => ZSTM.succeed[Option[Remote.Variable[_]]](Some(head))
          case _               => ZSTM.succeed[Option[Remote.Variable[_]]](None)
        })
        .commit

    override def getAllVariables: ZIO[Any, Nothing, Set[Remote.Variable[_]]] =
      all.toSet.commit
  }

  def inMemory: ZLayer[Any, Nothing, LocalContext] =
    ZLayer {
      for {
        vars <- TMap.empty[BindingName, List[Remote.Variable[_]]].commit
        all  <- TSet.empty[Remote.Variable[_]].commit
      } yield InMemory(vars, all)
    }

}
