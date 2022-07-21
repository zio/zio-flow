package zio.flow

case class VariableUsage(
  variables: Set[RemoteVariableName],
  locals: Set[LocalVariableName],
  bindings: Set[BindingName]
) {
  def union(other: VariableUsage): VariableUsage =
    VariableUsage(
      variables union other.variables,
      locals union other.locals,
      bindings union other.bindings
    )

  def removeBinding(name: BindingName): VariableUsage =
    this.copy(bindings = this.bindings - name)
}

object VariableUsage {
  val none: VariableUsage = VariableUsage(Set.empty, Set.empty, Set.empty)

  def variable(name: RemoteVariableName): VariableUsage = VariableUsage(Set(name), Set.empty, Set.empty)
  def local(name: LocalVariableName): VariableUsage     = VariableUsage(Set.empty, Set(name), Set.empty)
  def binding(name: BindingName): VariableUsage         = VariableUsage(Set.empty, Set.empty, Set(name))
}
