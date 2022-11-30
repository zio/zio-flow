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
| `key-value-store`       | selects the backend to use for _key value store_, can be either `rocksdb`, `cassandra`, `dynamodb` or `in-memory`. See the [backends](backends) page for more information. | 
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

For configuration of the _DynamoDb store_, check the documentation of the [zio-aws library](https://zio.dev/zio-aws/configuration).

#### HTTP retry policies


### REST API

## Metrics

## Custom operation executor
