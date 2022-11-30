---
id: execution
title: "Execution"
---

# Execution

The data structures and operators in the `zio-flow` library only _describe_ a ZIO Flow program. To execute them we need
a persistent executor configured with a chosen set of dependencies. Most of the executor implementation is in
the `zio-flow-runtime` module, with some backend-specific extra modules discussed in the [backends](backends) section.

There are two main ways to execute a ZIO Flow program:

- Embedding `zio-flow-runtime` in your own application, using the `PersistentExecutor` service
- Use the default _ZIO Flow Server_ which is an executable service application built on top of `zio-flow-runtime`

## Embedding the persistent executor

To embed the ZIO Flow executor in your own application, you need to add the following dependency:

```scala
libraryDependencies += "dev.zio" %% "zio-flow-runtime" % "@VERSION@"
```

Then you can initialize the `PersistentExecutor` service with the following method on its companion object:

```scala mdoc:invisible
import zio.{durationInt, ZLayer}
import zio.flow._
```

```scala
def make(gcPeriod: Duration = 5.minutes): ZLayer[
  DurableLog with
    KeyValueStore with
    Configuration with
    OperationExecutor with
    Serializer with
    Deserializer, Nothing, ZFlowExecutor]
```

This is a `ZLayer` creating a `ZFlowExecutor`. Let's see first what this interface is capable of, then we'll see how to
create the required dependencies.

The `ZFlowExecutor` interface has the following methods:

### `run`

`run` submits a flow to the executor, and waits until it completes. The failure/success of the flow is represented as
the failure/result of this `ZIO` effect.

To make this work ZIO Flow needs a `Schema` for both the error type and the result type. This is how the executor's
result can be converted to the expected type.

The `run` method expects that the provided `ZFlow` has no input requirements (it's `R` parameter is `Any`). If you need
to run a flow that requires an input, you can first use `.provide` on it to provide the input, and then run it.

Let's see an example, assuming that we have `ZFlowExecutor` in the environment of our ZIO program:

```scala mdoc:silent
import zio._
import zio.flow._
import zio.flow.runtime._

val flow: ZFlow[Int, String, Int] = ZFlow.input[Int].flatMap { n => 
  ZFlow.ifThenElse(n > 10)(ifTrue = ZFlow.succeed(1), ifFalse = ZFlow.fail("input is too low"))
}

def program: ZIO[ZFlowExecutor, String, Int] = 
  ZFlowExecutor.run(FlowId("test1"), flow.provide(5))
```

The `FlowId` is a unique identifier for _an execution_ of the flow. It is the caller's responsibility to generate a
fresh identifier in case the flow has to be started from scratch. If a flow with the given identifier is already
running, or it's persisted state is available, the execution of that flow will continue and the method will wait for the
result of that execution.

### `start` and `poll`

An alternative to `run` is to just _start_ the execution of a flow with the `start` method, without waiting for its
completion. The running workflow's state can be later queries with `poll`. The start method actually returns with
a `DurablePromise` that can be directly used to wait for the result of the flow, but it is much simpler to use
the `poll` method instead.

Another difference is that `start` and `poll` does not use the flow's error and result types. The polled result
represents both failures and errors as `DynamicValue` values. This is a generic data type from the `zio-schema` library
that can represent any value that has a `Schema`. By owning such a `Schema` you can convert back the dynamic value to
its original type. You can learn more about how `DynamicValue` is used in the internals of ZIO Flow in
the [internals](internals) page.

### `restartAll`

When the executor is initialized, it is not running any flows, even if there were previously running flows persisted
that could be resumed. To start running these persisted flows, you can call the `restartAll` method. This will load and
immediately resume every persisted ZIO Flow program that was previously running.

If you don't want this, you can resume the flows one by one if you know their `FlowId` by calling `run` or `start`.

### `delete`

Once a flow is completed, it's last persisted state, as well as it's results is still stored in the database, so
calling `poll` or `getAll` can return information about it. The `delete` method completely removes a _completed flow_
from the executor's persisted state.

### `pause`, `resume` and `abort`

A running flow can be _paused_ with `pause` and resumed any time with `resume`. A paused flow remains paused in case the
executor is restarted and `restartAll` is called.

A running (or paused) flow can be _aborted_ with the `abort` method. An aborted flow's result is
the `ExecutorError.Interrupted` error.

### `getAll`

The `getAll` method returns a _ZIO stream_ of `FlowId` and `FlowStatus` pairs, listing all the ZIO Flow programs the
executor knows about. The `FlowStatus` is an enumeration with the following values:

- `Running` - the flow is currently running
- `Paused` - the flow is currently paused
- `Suspended` - the flow is currently suspended in a transaction, waiting for a variable to change
- `Done` - the flow finished running either with an error or with a success

### `forceGarbageCollection`

Garbage collection in the context of a persistent executor is not about releasing items from the _memory_, but deleting
old persistent state from the database backend. This is executed periodically by the executor, but you can also trigger
it manually with the `forceGarbageCollection` method.

### Dependencies

The persistent executor layer depends on the following other ZIO _services_:

#### `DurableLog`

`DurableLog` is a persistent event log used for waiting for variables to change or promises to be completed. Currently
the only implementation is defined by the `DurableLog.layer` layer and it depends on an `IndexedStore`. `IndexedStore`
is provided by the [backend modules](backends) similar to`KeyValueStore`.

#### `KeyValueStore`

`KeyValueStore` is an interface for storing and retrieving information based on keys from a persistency solution. There
are multiple implementations available in the [backend modules](backends).

#### `Configuration`

The `Configuration` service stores the user defined configuration values, indexed by a `ConfigKey`, that can be accessed
by the ZIO Flow programs using `Remote.config`. The following implementations are available:

- `Configuration.inMemory` - stores the configuration in memory, useful mostly for testing. The initial state is empty.
- `Configuration.fromEnvironment` - by providing a mapping from `ConfigKey` to _system environment variable names_, this
  implementation provides configuration values for ZIO Flow programs directly from environment variables
- `Configuration.fromConfig` - uses ZIO's native configuration API to read configuration values from a given subsection
  of the provided configuration

#### `OperationExecutor`

The `OperationExecutor` service is responsible for executing `Operatotion`s, described on the [activities](activities)
page.

There are two built-in `OperatorExecutor` implementations in ZIO Flow:

- `DefaultOperationExecutor.layer` constructs an executor that supports the `Operation.HTTP` operations, implemented
  using the `zio-http` library
- `MockedOperationExecutor` is useful for testing flow, more information about it can be found on
  the [testing](testing#mocking-operations) page

The default operation executor requires you to provide HTTP retry policy configuration, that describes how each HTTP
requests are handling errors. More information about these policies can be found in the server configuration section
below.

#### `Serializer` and `Deserializer`

These two services define how the persisted data (both variables and flow state) is serialized and deserialized.
Serialization is based on _codecs_ provided by the `zio-schema` library.

Currently we provide two implementations for these services:

- `Serializer.json` and `Deserializer.json` - uses JSON serialization
- `Serializer.protobuf` and `Deserializer.protobuf` - uses Protobuf serialization

## ZIO Flow Server

_ZIO Flow Server_ is a ready to use executable server application that wraps the persistent executor and provides a (
HOCON) file based configuration to set it up, and a REST API for running and querying flows.

### Running the server

There is no packaged executable of the server at the moment, but later we are planning to provide a ready to use docker
container.

Today to run the server the easiest way is to clone the repository and run:

```bash
export ZIO_FLOW_SERVER_CONFIG=custom-config.conf
sbt zioFlowServer/run
```

### Configuration

The configuration file pointed by `ZIO_FLOW_SERVER_CONFIG` is a _HOCON file_.

The file has the following sections and values:

| Section                 | Description                                                                                                                                                                |
|-------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `port`                  | the port the server will listen on                                                                                                                                         |
| `key-value-store`       | selects the backend to use for _key value store_, can be either `rocksdb`, `cassandra`, `dynamodb` or `in-memory`. See the [backends](backends) page for more information. Currently the `key-value-store` is also used to store the _flow templates_ (discussed below). This is expected to move to its own configuration key in next versions.
| `indexed-store`         | selects the backend to use for _indexed store_, most of the time it should be the same as `key-value-store`                                                                | 
| `metrics.interval`      | defines the interval for collecting internal metrics                                                                                                                       | 
| `serialization-format`  | can be either `json` or `protobuf`                                                                                                                                         | 
| `gc-period`             | the interval                                                                                                                                                               |
| `flow-configuration`    | a list of key-value pairs of user-defined configuration provided to flows via `Remote.config`                                                                              |
| `policies` | definition of HTTP retry policies, explained in details below                                                                                                              |
| `rocksdb-key-value-store` | configuration for the RocksDB key value store, if it was selected by `key-value-store`                                                                                      |
| `rocksdb-indexed-store` | configuration for the RocksDB indexed store, if it was selected by `indexed-store`                                                                                          |
| `cassandra-key-value-store` | configuration for the Cassandra key value store, if it was selected by `key-value-store`                                                                                  |
| `cassandra-indexed-store` | configuration for the Cassandra indexed store, if it was selected by `indexed-store`                                                                                      |

For configuration of the _DynamoDb store_, check the documentation of
the [zio-aws library](https://zio.dev/zio-aws/configuration).

#### HTTP retry policies

The `policies.http` node contains two sub nodes:

- `default` is the default policy for all HTTP requests that are not customized by the `per-host` settings
- Items in `per-host` can override the default settings based on the request's host name

For each host (and the default settings) you can define the following settings:

- `max-parallel-request-count` is the maximum number of parallel requests that can be sent to the host
- `host-override` should only be used in the `per-host` configurations and it allows you to change the host name of the
  requests
- `retry-policies` is a list of retry policies, described below
- `circuit-breaker-policy` is an optional node describing how to reset the _circuit breaker_ for the host after it gets
  opened
- `timeout` is the maximum duration a request can take before get cancelled

Each element in `retry-policies` is a configuration object with the following properties:

`condition` specifies the condition when this specific retry policy should be used. The following condition types are
supported:

- `always` defines a retry policy that is going to match _all_ requests
- `for-specific-status` selects a single specific HTTP status code
- `for-4xx` defines a retry policy for cases when the server responded with any HTTP status code between 400-499
- `for-5xx` defines a retry policy for cases when the server responded with any HTTP status code between 500-599
- `open-circuit-breaker` defines a retry policy for the case when a request is blocked by an open _circuit breaker_
- `or` combines two conditions, defined in the `first` and `second` sub nodes

`retry-policy` defines how to retry the request; it is the same configuration structure as the one used
for `circuit-breaker-policy`, and we are going to define it later.

`break-circuit` is a boolean option. If it is `true`, when the condition of this retry policy is triggered, it will not
only retry the request but also report this as a failure for the _circuit breaker_. The circuit breaker will open the
circuit, making all further requests fail (with the condition `open-circuit-breaker`) for a given period.

For both the `retry-polcy` of each retry policy and for the `circuit-breaker-policy` describing the resetting behavior
of the circuit breaker the configuration structure looks the same:

- `fail-after` defines the maximum number of retries, or the maximum elapsed time spent for retries
- `repetition` defines how much time elapses between retries. it can be either a `fixed` time interval, or
  an `exponential` one
- `jitter` is a boolean configuration enabling some jittering for these configured intervals

### REST API

The _ZIO Flow Server_ provides a HTTP REST API for working with ZIO Flow programs. This section defines all the
available endpoints.

#### `GET /healthcheck`

Simple healthcheck endpoint to check that ZIO Flow server is running.

#### `GET /metrics`

Prometheus metrics. The list of available metrics is defined in the [metrics section](#metrics)

#### `GET /templates`

Get all the available _templates_ registered in the server. A _template_ in ZIO Flow server is a stored ZIO Flow program
that optionally can have an _input parameter_ as well. By storing these on the server they get an associated _template
ID_ and you can refer to this ID when starting a new flow instead of sending the whole serialized ZIO Flow program every
time.

The response JSON has the following structure:

```json
{
  "entries": [
    {
      "templateId": "xyz",
      "template": {
        "flow": {
          ...
        }
        "inputSchema": {
          ...
        }
      }
    },
    ...
  ]
}
```

#### `GET /templates/<templateId>`

Gets a single stored _flow template_ by its identifier.

#### `PUT /templates/<templateId>`

Stores a new _flow template_ by providing an identifier and posting the flow and input schema in the request's body.

#### `DELETE /templates/<templateId>`

Deletes a _flow template_ that was previously stored by the above defined `PUT` by its identifier.

#### `POST /flows`

Start executing a new _ZIO Flow program_.

The following JSON examples show the possibilities of this endpoint:

Starting execution of a ZIO Flow that has no parameters (`ZFlow.succeed(1)`) :

```json
{
  "Flow": {
    "flow": {
      "Return": {
        "Literal": {
          "Int": 1
        }
      }
    }
  }
}
```

Starting execution of a ZIO Flow that has a _required input parameter_ by providing this parameter's type (schema) and
the value as well (`ZFlow.input[Int]`):

```json
{
  "FlowWithParameter": {
    "flow": {
      "Input": {}
    },
    "schema": {
      "Other": {
        "toAst": {
          "Value": {
            "valueType": "int",
            "path": [],
            "optional": false
          }
        }
      }
    },
    "value": 1
  }
}
```

Starting a ZIO Flow program that is stored as a _flow template_ and does not require any parameters:

```json
{
  "Template": {
    "templateId": "template1"
  }
}
```

Starting a ZIO Flow program that is stored as a _flow template_ and requires a parameter as well:

```json
{
  "TemplateWithParameter": {
    "templateId": "template2",
    "value": 1
  }
}
```

The response JSON contains a field called `flowId` containing the started flow's identifier. It can be used to poll
for the result of the running flow, as well as pausing, resuming or aborting it.

```json
{
  "flowId": "xyz"
}
```

#### `GET /flows`

Gets a list of all the flows handled by the server's executor, together with their current status.

The response is a mapping from _flow ID_ to _status_:

```json
{
  "flow1": "Running",
  "flow2": "Paused",
  "flow3": "Done",
  "flow4": "Suspended"
}
```

(in reality the flow IDs are UUIDs)

#### `GET /flows/<flowId>`

Polls the status of a given flow. The following examples demonstrate the possible response JSONs for this request:

When the flow is still running:

```json
{
  "Running": {}
}
```

If the flow died with an internal error, or was _aborted_ (could be any other `ExecutionError`, not just `Interrupted`):

```json
{
  "Died": {
    "value": {
      "Interrupted": {}
    }
  }
}
```

If the flow finished running and succeeded with a value:

```json
{
  "Succeeded": {
    "value": 1
  }
}
```

If the flow finished running and failed with a value:

```json
{
  "Failed": {
    "value": "flow failed!"
  }
}
```

#### `DELETE /flows/<flowId>`

Deletes an already completed _flow_ from the executor.

#### `POST /flows/<flowId>/pause`

Pauses a running _flow_.

#### `POST /flows/<flowId>/resume`

Resumes a previously paused _flow_.

#### `POST /flows/<flowId>/abort`

Aborts a running flow.

## Metrics

Many components of ZIO Flow report _metrics_ using ZIO's built-in metrics API. _ZIO Flow Server_ exposes these metrics
for Prometheus via the `/metrics` endpoint. In case of using the executor embedded in your own application, the metrics
can be sent to any metrics backend that supports the ZIO metrics API.

The following list contains all the metrics reported by various components of the ZIO Flow runtime:

- `zioflow_remote_evals` is a counter for (_tracked Remotes_)[remote#metrics]
- `zioflow_remote_eval_time_ms` is a histogram for _tracked Remotes_
- `zioflow_started_total` is a counter incremented every time a flow is started executing (either new or restarted)
- `zioflow_active_flows` is a gauge containing the actual number of running or suspended flows
- `zioflow_operations_total` is a counter for each primitive `ZFlow` operation that was executed
- `zioflow_transactions_total` is a counter for the number of committed, failed or retried transactions
- `zioflow_finished_flows_total` is a counter increased when a flow finishes with either success, failure or death
- `zioflow_executor_error_total` is a counter for different executor errors
- `zioflow_state_size_bytes` is a histogram for the serialized workflow state snapshots in bytes
- `zioflow_variable_access_total` is a counter increased when a remote variable is accessed (read, write or delete)
- `zioflow_variable_size_bytes` is a histogram of the serialized size of remote variables in bytes
- `zioflow_finished_flow_age_ms` is a histogram of the duration between submitting the workflow and completing it
- `zioflow_total_execution_time_ms` is a histogram of the total time a workflow was in either running or suspended state
  during its life
- `zioflow_suspended_time_ms` is a histogram of time fragments a workflow spends in suspended state
- `zioflow_gc_time_ms` is a histogram of the time a full persistent garbage collection run takes
- `zioflow_gc_deletion` is a counter for the number of remote variables deleted by the garbage collector
- `zioflow_gc` is a counter for the number of persistent garbage collector runs
- `zioflow_http_responses_total` is a counter for the number of HTTP operations performed
- `zioflow_http_response_time_ms` is a histogram of the HTTP operation response times
- `zioflow_http_failed_requests_total` is a counter for the number of failed HTTP requests
- `zioflow_http_retried_requests_total` is a counter for the number of retried HTTP requests per host- ``

The ZIO Flow Server also reports the _default JVM metrics_ provided by ZIO Core.

## Custom operation executor

Writing a custom implementation of `OperationExecutor` is the intended way to extend ZIO Flow with custom ways to
interact with the outside world. An `OperationExecutor` gets an input value and an `Operation[Input, Output]` value, and
it has to provide a _ZIO effect_ that produces a `Result` or fails with an `ActivityError`.

Support for custom operation executors is not fully ready in the current version of ZIO Flow - although you can provide
a custom implementation when embedding the persistent executor, you cannot extend the _schema_ of the `Operation` type
to allow serializing custom operations.

This limitation will be fixed in future releases. 
