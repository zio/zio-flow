---
id: remote
title: "Remote"
---

# Remotes

## Overview
### Remote Values

The `Remote` data type forms the backbone of ZIO Flow and allows us to safely describe values that may be part of computations on multiple remote nodes.

In ordinary Scala we are used to working with values defined by the Scala library such as `Int` and `String` as well as user defined data types such as a `User` and interfaces such as a `UserService`. The problem with using these data types in a distributed in a setting where we need to perform distributed or resilient workflows is that these data types may not actually be safely serializable and cause our programs to fail at runtime.

Even if we are extremely diligent about trying to avoid this, it can be easy to avoid accidentally closing over other variables, resulting in data that is either not serializable or takes up much more space than we intended. This is an infamous problem with frameworks like Spark despite their best efforts to avoid it.

ZIO Flow handles this issue in a principled way with its `Remote` data type, which is a _description_ of a value that may potentially exist on a remote node. This way you can easily look at any value and tell just from its type whether it is a `Remote` value that is safe to use in resilient, distributed computations or an ordinary value that is fine to use on a single node but does not provide these guarantees.

Generally ZIO Flow will require that the values we work with be `Remote` values so that it can safely replicate them across multiple nodes or reload them from durable storage in the event of a failure.

For example, compare the signature of the `map` operator on `ZIO` and `ZFlow`:

```scala mdoc
import zio.flow._

trait ZIO[-R, +E, +A] {
  def map[B](f: A => B): ZIO[R, E, B]
}

trait ZFlow[-R, +E, +A] {
  def map[B](f: Remote[A] => Remote[B]): ZFlow[R, E, B]
}
```

There are a couple of things that should jump out at you from this.

First, the method signatures are nearly identical! You will find this frequently with ZIO Flow, where most of the operators you are familiar with from `ZIO` like `map` also exist on `ZFlow` so if you know ZIO you are already ready to go.

Second, you will notice that the one thing that differs between these type signatures is that whereas `ZIO#map` accepts a function `A => B`, `ZFlow#map` accepts a function `Remote[A] => Remote[B]`. This makes sense because `ZIO` describes a workflow on a single machine whereas `ZFlow` describes a resilient, distributed, computation, so the values we are working with must be of the form `Remote[A]` rather than `A`.

### Working With Remote Values

At this point you might be worried that despite these benefits, working with remote values could involve additional boilerplate. We know how to add two `Int` values, but how do we add two `Remote[Int]` values?

Fortunately, ZIO Flow goes to great lengths to make working with `Remote` values as ergonomic as possible, so we can almost "forget" that we are working with something other than ordinary values at all.

The main way ZIO Flow does this is by providing operators on remote values that mirror the operators on ordinary values. For example, we can add two `Remote[Int]` values by simply adding the two values together:

```scala mdoc:reset
import zio.flow._

val left: Remote[Int] = Remote(1)
val right: Remote[Int] = Remote(1)

val sum: Remote[Int] = left + right
```

We see here that we can use the `apply` constructor on `Remote` to lift any existing value into a remote value as long as there is a `Schema` for it. We'll talk more about schemas below but you can think of a `Schema` as describing the "structure" of some Scala type as a value, allowing us to safely serialize it and deserialize it, among other things.

In fact ZIO Flow will convert ordinary values into remote values for us automatically when needed. So we could also do this:

```scala mdoc
val flow: ZFlow[Any, Nothing, Int] =
  ZFlow.succeed(1)

val incrementedFlow: ZFlow[Any, Nothing, Int] =
  flow.map(_ + 1)
```

Notice that here the function we provided to `ZFlow#map` looked identical to the one we would provide to `ZIO#map` in a `ZIO` application. This is exactly the interface we want and lets use "forget" in most cases that we are working with the world of `Remote` values instead of the world of normal values.

This also reflects the idea that the `Remote` world is a "mirror" of the world of ordinary values and absent underlying implementation limitations (e.g. arbitrary Scala functions are not serializable) the interface for working with `Remote` values should look and feel as similar to the interface for working with ordinary values as possible.

This also means that if you know how to do something in ordinary Scala you also already know how to do it with `Remote` values.

We are definitely working on ensuring that the experience of working with `Remote` values is as good as working with ordinary values. So if you see an area where this is not the case please reach out to us on Discord or open an issue so that we can improve it!

### Working With Schemas

As discussed above, a `Schema` describes the "structure" of some Scala type as a value. This lets us know how to serialize and deserialize the value, as well as providing other useful functional like migrations between two versions of a data type.

Schemas are provided by `ZIO Schema`, a library that is exclusively focused on defining these schemas, providing schemas for various data types, and enabling useful functionality with them. When we are working with data types defined by the Scala or Java standard libraries schemas should already automatically be available as long as the data type can be safely serialized and deserialized.

ZIO Flow uses those schemas internally and as long as they exist things "just work". So you may have noticed that we didn't have to do anything with schemas when we did `Remote(1)` because a schema for `Int` values already exists.

The one time you will typically have to work with schemas directly is in defining schemas for your own custom data types. Fortunately this is very easy!

In idiomatic Scala and in ZIO Flow we always define our data types as either a `case class` to represent data that has one thing _and_ has another thing (e.g. a credit card has a name and a number) or a `sealed trait` to represent data that is one _or_ is another thing (e.g. a payment method is either credit card or check).

```scala mdoc
sealed trait PaymentMethod

case class CreditCard(name: String, number: String) extends PaymentMethod
case class Check(name: String, routing: String) extends PaymentMethod
```

Notice how even in this simple example we have combined these two techniques to model a more complex domain with both of these types.

As long as we model our data this way, we can automatically generate schemas for our data types using the `DeriveSchema.gen` operator. For example, let's see how we could generate a schema for our `PaymentMethod` data type:

```scala mdoc
import zio.schema._

object PaymentMethod {
  implicit val schema: Schema[PaymentMethod] = DeriveSchema.gen
}
```

We should be sure to define our schemas in the companion objects of the data types they describe the structure of, like we did by putting the `schema` for `PaymentMethod` in the `PaymentMethod` companion object here. We should also declare it as an `implicit val` so that the compiler can automatically find the schema when needed.

Other than that the hardest thing is remembering to import ZIO Schema!

With the schema defined this way, we can construct remote versions of `PaymentMethod` values just like we did for `Int` values:

```scala
val remotePaymentMethod: Remote[PaymentMethod] =
  Remote(CreditCard("John Doe", "123456789"))
```

With this, you know everything you need to work with remote values as part of writing your resilient, distributed application!

## Constructing remotes

## Accessing custom types with optics

## Writing remote functions

### Recursion

### Debugging 

### Metrics

## List of supported remote types
