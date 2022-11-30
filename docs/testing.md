---
id: testing
title: "Testing"
---

# Testing

ZIO Flow has some tools and practices helping the testing flows, remotes, and backend implementations.

## Testing remotes

The [debugging section on the Remote page](remote#debugging) shows how you can annotate your remote functions with debug
logs, which is useful for understanding how a more complex remote function works.

But what about _evaluating_ a remote expression in a standalone ZIO test case?

First make sure the `zio-flow-runtime` library is added as a dependency, as we no longer just _define_ remotes but want
to evaluate them as well:

```scala
libraryDependencies += "dev.zio" %% "zio-flow-runtime" % "@VERSION@"
```

Then we can write a ZIO test suite that uses a `Remote`s `.eval[T]` or `.evalDynamic` method to evaluate the remote.

```scala mdoc
import zio.{durationInt, ZLayer}
import zio.test._
import zio.flow._
import zio.flow.runtime._
import zio.flow.runtime.internal._

object RemoteSpec extends ZIOSpecDefault {
  override def spec =
    suite("Remote test example")(
      test("A remote number evaluates to the expected value") {         
        for {
            result <- Remote(1234).eval[Int]
        } yield assertTrue(result == 1234)                 
      }
    ).provide(ZLayer(InMemoryRemoteContext.make), LocalContext.inMemory)
}
```

For evaluating a remote we need a `RemoteContext` and a `LocalContext` provided. Normally these are provided by the
persistent executor, but for our tests we can use the above demonstrated two layers.

## Testing flows

For executing `ZFlow` programs in test suites we need to initialize a real `PersistentExecutor` as described on
the [execution page](execution).

The recommended dependencies to provide to it are the in-memory implementations:

```scala mdoc:silent
import zio.flow.runtime._

IndexedStore.inMemory
DurableLog.layer
KeyValueStore.inMemory
Configuration.inMemory
```

### Mocking operations

ZIO Flow provides a special `OperationExecutor` implementation to be used in tests, called `MockedOperationExecutor`.
This implementation is defined in the `zio-flow-test` module:

```scala
libraryDependencies += "dev.zio" %% "zio-flow-test" % "@VERSION@"
```

A separate _mocked operation executor_ has to be created for each test case using the `MockedOperationExecutor.make`
function. This takes a `MockedOperation` as an input, which describes all the expected _operations_ the ZIO Flow program
will make. The created `OperationExecutor` can be provided to the `PersistentExecutor` then before running the tested
flow.

The `MockedOperation` lives in the `zio.flow.mock` package and supports the following cases.

#### Matching a specific HTTP operation

Take the following example:

```scala mdoc:silent
import zio.flow.mock._
import zio.test.Assertion._

val mock1 = MockedOperation.Http(
  urlMatcher = equalTo("http://activity1"),
  methodMatcher = equalTo("GET"),
  inputMatcher = equalTo(1),
  result = () => 100,
  duration = 2.seconds
)
```

A mocked operation is matched by a couple of ZIO Test assertions (`equalTo` in this case). If it matches the operation
performed by an activity, it will return the provided result, and it will _sleep_ for given duration to simulate the
execution time of a remote operation.

There are _combinators_ defined on `MockedOperation` that allows defining more than one possible operations to be
matched.

If you expect an operation to be called more than once, this can be specified by using the `.repeated` modifier:

```scala mdoc:silent
val mock2 = mock1.repeated(atMost = 10)
```

If two different mocked operations can be expected and you don't know their order in advance, use the `orElse` or `|`
combinator:

```scala mdoc:silent
val mock3 = MockedOperation.Http(
  urlMatcher = equalTo("http://activity2"),
  methodMatcher = equalTo("GET"),
  inputMatcher = anything,
  result = () => 0,
  duration = 1.seconds
)

val mock4 = mock2 | mock3
```

And finally you may want to define that you expect one particular operation to be called _after_ another one. For this
you can use the `andThen` or `++` combinators:

```scala mdoc:silent
val mock5 = mock1 ++ mock3
```

## Testing serialization

When [creating _activity libraries_](activities.md#write-your-own) we may want to ensure that the data models used in
the activities can be serialized to JSON and read back to get the same value, or similarly that they can be encoded in
form-url payload and get the expected encoded string.

The `zio-flow-test` module provides two helper assertions to implement these tests. Add the following dependency to use
them:

```scala
libraryDependencies += "dev.zio" %% "zio-flow-test" % "@VERSION@"
```

and then import the assertions for your tests:

```scala
import zio.flow.test.{assertFormUrlEncoded, assertJsonSerializable}
```

## Testing backends

There are reusable tests for _key-value store_ and _indexed store_ implementations. To use them, add the following
dependency:

```scala
libraryDependencies += "dev.zio" %% "zio-flow-runtime-test" % "@VERSION@"
```

Use the `KeyValueStoreTests` class to create a ZIO Test suite for your implementation. For example the built-in
DynamoDb implementation's test is defined like this:

```scala
override def spec: Spec[TestEnvironment, Any] =
  KeyValueStoreTests[DynamoDb](
    "DynamoDbKeyValueStoreSpec",
    initializeDb = createKeyValueStoreTable(tableName)
  ).tests.provideSomeLayerShared[TestEnvironment](dynamoDbKeyValueStore)
```

and for testing the _indexed store_:

```scala
override def spec: Spec[TestEnvironment with Scope, Any] =
  IndexedStoreTests[DynamoDb](
    "DynamoDbIndexedStore",
    initializeDb = createIndexedStoreTable(DynamoDbIndexedStore.tableName)
  ).tests.provideSomeLayerShared[TestEnvironment](dynamoDbIndexedStore)
```

In case you don't need any initialization effect for your database, just pass `ZIO.unit` to the `initializeDb`
parameter.