package zio.flow

case class VariableUsage(
  variables: Set[RemoteVariableName],
  bindings: Set[BindingName]
) {
  def union(other: VariableUsage): VariableUsage =
    VariableUsage(
      variables union other.variables,
      bindings union other.bindings
    )

  def unionAll(others: Seq[VariableUsage]): VariableUsage =
    VariableUsage(
      others.foldLeft(variables)(_ union _.variables),
      others.foldLeft(bindings)(_ union _.bindings)
    )

  def removeBinding(name: BindingName): VariableUsage =
    this.copy(bindings = this.bindings - name)
}

object VariableUsage {
  val none: VariableUsage = VariableUsage(Set.empty, Set.empty)

  def variable(name: RemoteVariableName): VariableUsage = VariableUsage(Set(name), Set.empty)
  def binding(name: BindingName): VariableUsage         = VariableUsage(Set.empty, Set(name))
}
