package zio.flow

sealed trait FlowError 
object FlowError {
  type FlowNotFound = FlowNotFound.type 
  type ActivityExecutionError = ActivityExecutionError.type

  case object FlowNotFound extends FlowError 
  case object ActivityExecutionError extends FlowError 
}