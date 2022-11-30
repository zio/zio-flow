---
id: internals
title: "Internals"
---

# Internals

This page contains information about some implementation details of ZIO Flow which are not necessary to know about for
regular use of the system.

## The Remote type

The `Remote` data type is a core concept in ZIO Flow. The most important feature a `Remote` provides is that it can be
_evaluated_. It is important to understand that evaluating a `Remote[A]` does not produce a type of `A`!
The `evaluateDynamic` function provides a `DynamicValue`, which is a generic data type from `zio-schema`.
This `DynamicValue` must be something isomorphic to the `A` type the remote value represents. So in case we have
a `Schema[A]` we can convert this `DynamicValue` back to a typed `A`. `Remote` itself contains a helper method
called `.eval` that requires an implicit schema for `A` and thus it returns with a typed value instead of
the `DynamicValue`. This method is only used in special cases in the executor though, because the executor does not
always have a _schema_ for the values it is working with.

### Schemas and dynamic values

This "limitation" allows us to work with user-defined types in ZIO Flow programs without the need to inject these types
into the server's classpath! Another reason is that we cannot serialize a `Schema` that defines transformations via
Scala functions. If we just use the dynamic values on the server side we can run all the flow steps and only convert to
actual typed representation when necessary.

There are three main cases when converting to typed value is required:

- When a flow finishes running, the user may want to get a typed result. This is OK because it happens on the "client
  side" (where the flow is defined, not on the executor). This is the process where our custom types are defined so we
  have the necessary `Schema` to convert the dynamic result value to the expected one.
- When calling external services, for example using `Operation.Http`, the serialization of the parameters like request
  body needs to know its `Schema`. This is a schema that is serialized as part of the flow, because it is used on the
  server side. So this is _not_ the same schema that the client side has, but it is still necessary because it may
  contain some additional information required for the serialization codec to produce the expected format.
- When performing some server-side operations that are "native". For example performing numerical operations is
  something that is implemented by calling the underlying Java implementation for those numeric operations. To do so, we
  need to convert the `DynamicValue` to the given numeric type to be able to call the native implementation.

Let's go through an example!

We define a case class with a schema:

```scala mdoc
import zio.durationInt
import zio.flow._
import zio.flow.operation.http._
import zio.schema._

final case class Example1(name: String, value: Int)
object Example1 {
  implicit val schema = DeriveSchema.gen[Example1]
}
```

then we store this as a `Remote` value:

```scala mdoc
val remote1 = Remote(Example1("something", 1))
```

This will first convert the `Example1` value to a `DynamicValue` and then wrap it in a `Remote.Literal` constructor.
This particular remote constructor does not store anything else than the dynamic value. The schema of `Example1` is not
transferred to the executor. Evaluating it just returns the dynamic value itself. To call `.eval` and get
back `Example1` we need to provide the schema, which we only have on the definition side.

Now let's assume that we have an _activity_ that requires an `Example1` value as its input:

```scala mdoc
val activity1: Activity[Example1, Unit] =
  Activity(
    "example activity",
    "",
    operation = Operation.Http(
      host = "https://example.com",
      API
        .post("test")
        .input[Example1]
        .output[Unit]
    ),
    check = Activity.checkNotSupported,
    compensate = Activity.compensateNotSupported
  )
```

We can pass `remote1` to `activity1` to perform the HTTP request:

```scala mdoc
val flow1 = activity1(remote1)
```

This translates to a `ZFlow.RunActivity` value that connects a `Remote` and an `Activity`. The activity, however,
through the `operation` field, stores the input and output schema. This means that when we serialize `flow1`, we also
serialize `Example1.schema` as part of it. Serializing a schema means converting it to a `MetaSchema`, then on the
server side we deserialize a `MetaSchema` and produce a `Schema` from it. On the server side, however, we don't know
anything about the `Example1` Scala class at all! So the deserialized schema on the server side will be
a `GenericRecord`, which stores its fields in a `ListMap[String, _]`. That's a representation isomorphic to the original
case class, so the server can work with it.

### Remote function application

Let's see how _remote function application works_.

First we define a remote function as a regular Scala function:

```scala mdoc
val f1 = (x: Remote[Int]) => x + 1 
```

This is not a serializable `Remote` value yet, it is a Scala function. So we have to first convert it to
a `Remote.UnboundRemoteFunction`:

```scala mdoc
val f2 = Remote.UnboundRemoteFunction.make(f1)
```

This creates a `Remote.Unbound` representing the unbound input parameter of the function, and _calls_ the function with
it, injecting this "hole" in our expression tree.

Then we can _bind_ the parameter of this function by calling `.apply` on `f2`:

```scala mdoc
val f3 = f2(100)
```

This way we get a `Remote.Bind` which stores the parameter value (a `Remote.Literal` holding `100`)

### What is implemented as a Remote and what not?

There are many `Remote` constructors for some primitive operations we support, but there are even more functionalities
implemented in other classes, such as `BinaryOperators`, `RemoteConversions`, etc. The primary distinction is that if
something can be implemented by staying on the level of `DynamicValue`s, it is a `Remote` constructor. If the
calculation requires converting the dynamic values to some typed value first, it is implemented in one of the supporting
classes such as the ones mentioned above.

## Persistent variables and promises

## Executor state management

## Transactions

## Garbage collection
