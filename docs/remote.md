---
id: remote
title: "Remote"
---

# Remotes

## Overview

### Remote Values

The `Remote` data type forms the backbone of ZIO Flow and allows us to safely describe values that may be part of
computations on multiple remote nodes.

In ordinary Scala we are used to working with values defined by the Scala library such as `Int` and `String` as well as
user defined data types such as a `User` and interfaces such as a `UserService`. The problem with using these data types
in a distributed in a setting where we need to perform distributed or resilient workflows is that these data types may
not actually be safely serializable and cause our programs to fail at runtime.

Even if we are extremely diligent about trying to avoid this, it can be easy to avoid accidentally closing over other
variables, resulting in data that is either not serializable or takes up much more space than we intended. This is an
infamous problem with frameworks like Spark despite their best efforts to avoid it.

ZIO Flow handles this issue in a principled way with its `Remote` data type, which is a _description_ of a value that
may potentially exist on a remote node. This way you can easily look at any value and tell just from its type whether it
is a `Remote` value that is safe to use in resilient, distributed computations or an ordinary value that is fine to use
on a single node but does not provide these guarantees.

Generally ZIO Flow will require that the values we work with be `Remote` values so that it can safely replicate them
across multiple nodes or reload them from durable storage in the event of a failure.

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

First, the method signatures are nearly identical! You will find this frequently with ZIO Flow, where most of the
operators you are familiar with from `ZIO` like `map` also exist on `ZFlow` so if you know ZIO you are already ready to
go.

Second, you will notice that the one thing that differs between these type signatures is that whereas `ZIO#map` accepts
a function `A => B`, `ZFlow#map` accepts a function `Remote[A] => Remote[B]`. This makes sense because `ZIO` describes a
workflow on a single machine whereas `ZFlow` describes a resilient, distributed, computation, so the values we are
working with must be of the form `Remote[A]` rather than `A`.

### Working With Remote Values

At this point you might be worried that despite these benefits, working with remote values could involve additional
boilerplate. We know how to add two `Int` values, but how do we add two `Remote[Int]` values?

Fortunately, ZIO Flow goes to great lengths to make working with `Remote` values as ergonomic as possible, so we can
almost "forget" that we are working with something other than ordinary values at all.

The main way ZIO Flow does this is by providing operators on remote values that mirror the operators on ordinary values.
For example, we can add two `Remote[Int]` values by simply adding the two values together:

```scala mdoc:reset
import zio.flow._

val left: Remote[Int] = Remote(1)
val right: Remote[Int] = Remote(1)

val sum: Remote[Int] = left + right
```

We see here that we can use the `apply` constructor on `Remote` to lift any existing value into a remote value as long
as there is a `Schema` for it. We'll talk more about schemas below but you can think of a `Schema` as describing the "
structure" of some Scala type as a value, allowing us to safely serialize it and deserialize it, among other things.

In fact ZIO Flow will convert ordinary values into remote values for us automatically when needed. So we could also do
this:

```scala mdoc
val flow: ZFlow[Any, Nothing, Int] =
  ZFlow.succeed(1)

val incrementedFlow: ZFlow[Any, Nothing, Int] =
  flow.map(_ + 1)
```

Notice that here the function we provided to `ZFlow#map` looked identical to the one we would provide to `ZIO#map` in
a `ZIO` application. This is exactly the interface we want and lets use "forget" in most cases that we are working with
the world of `Remote` values instead of the world of normal values.

This also reflects the idea that the `Remote` world is a "mirror" of the world of ordinary values and absent underlying
implementation limitations (e.g. arbitrary Scala functions are not serializable) the interface for working with `Remote`
values should look and feel as similar to the interface for working with ordinary values as possible.

This also means that if you know how to do something in ordinary Scala you also already know how to do it with `Remote`
values.

We are definitely working on ensuring that the experience of working with `Remote` values is as good as working with
ordinary values. So if you see an area where this is not the case please reach out to us on Discord or open an issue so
that we can improve it!

### Working With Schemas

As discussed above, a `Schema` describes the "structure" of some Scala type as a value. This lets us know how to
serialize and deserialize the value, as well as providing other useful functional like migrations between two versions
of a data type.

Schemas are provided by `ZIO Schema`, a library that is exclusively focused on defining these schemas, providing schemas
for various data types, and enabling useful functionality with them. When we are working with data types defined by the
Scala or Java standard libraries schemas should already automatically be available as long as the data type can be
safely serialized and deserialized.

ZIO Flow uses those schemas internally and as long as they exist things "just work". So you may have noticed that we
didn't have to do anything with schemas when we did `Remote(1)` because a schema for `Int` values already exists.

The one time you will typically have to work with schemas directly is in defining schemas for your own custom data
types. Fortunately this is very easy!

In idiomatic Scala and in ZIO Flow we always define our data types as either a `case class` to represent data that has
one thing _and_ has another thing (e.g. a credit card has a name and a number) or a `sealed trait` to represent data
that is one _or_ is another thing (e.g. a payment method is either credit card or check).

```scala mdoc
sealed trait PaymentMethod

case class CreditCard(name: String, number: String) extends PaymentMethod
case class Check(name: String, routing: String) extends PaymentMethod
```

Notice how even in this simple example we have combined these two techniques to model a more complex domain with both of
these types.

As long as we model our data this way, we can automatically generate schemas for our data types using
the `DeriveSchema.gen` operator. For example, let's see how we could generate a schema for our `PaymentMethod` data
type:

```scala mdoc
import zio.schema._

object PaymentMethod {
  implicit val schema = DeriveSchema.gen[PaymentMethod]
}
```

We should be sure to define our schemas in the companion objects of the data types they describe the structure of, like
we did by putting the `schema` for `PaymentMethod` in the `PaymentMethod` companion object here. We should also declare
it as an `implicit val` so that the compiler can automatically find the schema when needed.

Other than that the hardest thing is remembering to import ZIO Schema!

With the schema defined this way, we can construct remote versions of `PaymentMethod` values just like we did for `Int`
values:

```scala
val remotePaymentMethod: Remote[PaymentMethod] =
  Remote(CreditCard("John Doe", "123456789"))
```

With this, you know everything you need to work with remote values as part of writing your resilient, distributed
application!

## Constructing remotes

Let's see the specific ways to construct a `Remote` value in ZIO Flow.

The simplest way is to use the `Remote` constructor to capture any Scala value that has a `Schema` available:

```scala mdoc:silent
Remote(0)
Remote("hello world")
Remote(CreditCard("John Doe", "123456789"): PaymentMethod)
Remote(List(1, 2, 3))
```

What if we already have one or more `Remote` values and want to wrap them in a standard type like `Option`, `Either`
or `List`? There are
constructors on the `Remote` object for that:

```scala mdoc:silent
Remote.none[String]
Remote.some(Remote("hello world"))
Remote.nil[Int]
Remote.list(Remote(1), Remote(2), Remote(3))
Remote.left[String, Int](Remote("failed"))
Remote.right[String, Int](Remote(11))
Remote.emptyMap[String, Int]
Remote.map(Remote(("x", 1)), Remote(("y", 2)))
Remote.emptySet[Int]
Remote.set(Remote(1), Remote(2), Remote(3))
Remote.emptyChunk[Double]
Remote.chunk(Remote(1.2), Remote(3.4), Remote(5.6))
Remote.tuple3(Remote(1), Remote("hello"), Remote(0.1))
```

In many cases you don't have to explicitly create a `Remote` at all! In case the compiler knows a value has to have the
type `Remote[A]`, any value of type `A` will be automatically converted:

```scala mdoc:silent
val x: Remote[Int] = 1
```

## Accessing custom types with optics

As we saw above, custom types such as case classes and sealed traits are supported if they have an associated _schema_.
This can be enough if all we need is to pass a custom value to our flow, let's say to send it to an external service.
But what if we need to extract information from a custom type, or we need to construct it from other remote values?

This problem is solved by ZIO Flow's optics support which is built on top of _ZIO Schema accessors_.

### Case classes

For a custom case class, first we need to define accessors for all its fields:

```scala mdoc
final case class Person(name: String, age: Int)

object Person {
  implicit val schema = DeriveSchema.gen[Person]
  val (name, age) = Remote.makeAccessors[Person]
}
```

Note that when working with _accessors_, it is important that we don't specify an explicit type for the schema. This for
example would not work:

```scala
// Wrong example!!!
object Person {
  implicit val schema: Schema[Person] = DeriveSchema.gen
  val (name, age) = Remote.makeAccessors[Person]
}
// Wrong example!!!
```

The reason is that `DeriveSchema` generates a more specific type than `Schema[Person]` and `makeAccessors` needs this
information to work properly.

Then we can use these accessors to get the fields of a remote value of our custom type:

```scala mdoc:silent
val person1 = Remote(Person("John", 30))
val extractedName: Remote[String] = Person.name.get(person1)
val extractedAge: Remote[Int] = Person.age.get(person1)
```

The `set` method on the accessors can be used to manipulate the fields of a remote value:

```scala mdoc:silent
val sourceName: Remote[String] = "John"
val sourceAge: Remote[Int] = 30
val person2 = Person.name.set(Person.age.set(Remote(Person("", 0)))(sourceAge))(sourceName)
```

### Sealed traits

Similarly for sealed traits we can define accessors for all its subtypes. Using the earlier defined `PaymentMethod`
type:

```scala mdoc:silent
val (creditCard, check) = Remote.makeAccessors[PaymentMethod]
```

then we can use these accessors to check if a remote value of `PaymentMethod` is a `CreditCard` or a `Check`:

```scala mdoc:silent
val paymentMethod: Remote[PaymentMethod] = Remote[PaymentMethod](CreditCard("John Doe", "123456789"))
val maybeCreditCard: Remote[Option[CreditCard]] = creditCard.get(paymentMethod)
```

## Accessing configuration

ZIO Flow provides a way to access configuration values from within a flow. This can be used for example to pass access
tokens and other sensitive information to the flows.

To access a configured value, we can use the special `Remote.config` constructor:

```scala mdoc:silent
Remote.config[String](ConfigKey("example_api_token"))
```

The [execution section](execution) explains how these configuration values can be injected to the executed flows.

## Writing remote functions

Just like in a regular Scala program, functions are also values, so we can define _remote functions_. This is often used
in ZIO Flow's built-in operators. One example is the `map` operation that can transform the result of a flow:

```scala mdoc:silent
ZFlow.succeed(1).map(_ + 1)
```

Here the `_ + 1` is a remote function! Its type is `Remote[Int] => Remote[Int]`.

In this section we will see some helpers available for defining such remote functions.

### Bind

In a regular Scala function we could use _local variables_ to store intermediate results to avoid calculating them
multiple times:

```scala mdoc:silent
val scalaFun1: (Int => Int) =
  (input: Int) => {
    val a: Int = input * 2
    a + a
  }
```

What if we do the same in a remote function?

```scala mdoc:silent
val remoteFun1: (Remote[Int] => Remote[Int]) =
  (input: Remote[Int]) => {
    val a: Remote[Int] = input * 2
    a + a
  }
```

this will compile but remember that `Remote[Int]` is just a _serializable description_ of a value. Storing `input * 2`
in `a` does not actually perform the multiplication! So this style can improve readability of our code, but when the
executor calculates the actual value of this remote function, it will have to calculate `input * 2` twice.

The solution is to use the `bind` method on `Remote`:

```scala mdoc:silent
val remoteFun2: (Remote[Int] => Remote[Int]) =
  (input: Remote[Int]) => 
    Remote.bind(input * 2) { a =>
      a + a
    }
```

### Conditional expressions

If we have a remote boolean value and want to choose between two possible remote values based on it, we cannot use
Scala's `if` expression as the `Remote[Boolean]` is just a description of a computation, it does not have a value yet.
Instead we can use the `ifThenElse` method on `Remote`:

```scala mdoc:silent
Remote.config[Boolean](ConfigKey("feature1"))
  .ifThenElse(
    ifTrue = Remote(1), 
    ifFalse = Remote(2)
  )
``` 

Similarily we cannot do pattern matching on values, but we can use the `match` helper function on `Remote` to achieve
something similar:

```scala mdoc:silent
import java.time.temporal.ChronoUnit

def convert(nanos: Remote[Long], unit: Remote[ChronoUnit]): Remote[Long] =
  unit.`match`(
    ChronoUnit.NANOS   -> nanos,
    ChronoUnit.SECONDS -> nanos / 1000L,
    ChronoUnit.MILLIS  -> nanos / 1000000L
  )(Remote.fail("Unsupported unit"))
```

### Recursion

As explained in connection with the [ZFlow](zflow) type, recursive functions need special support in ZIO Flow to ensure
they are serializable.

Let's see an example! The following definition of `List#grouped` splits a remote list of elements into sublists of the
given length:

```scala mdoc:silent
def grouped[A](source: Remote[List[A]], size: Remote[Int]): Remote[List[List[A]]] =
  Remote.recurse[List[A], List[List[A]]](source) { (input, rec) =>
    input.isEmpty.ifThenElse(
      Remote.nil,
      Remote.bind(input.splitAt(size)) { splitPair =>
        splitPair._1 :: rec(splitPair._2)
      }
    )
  }
```

### Debugging

Any remote value can be annotated with the `.debug("message")` syntax to print the value to the executor's log when it
gets evaluated. This can be used to debug complex remote functions during the development of a ZIO flow program.

For example we may annotate parts of the above defined `grouped` function:

```scala mdoc:silent
def groupedDebug[A](source: Remote[List[A]], size: Remote[Int]): Remote[List[List[A]]] =
  Remote.recurse[List[A], List[List[A]]](source.debug("source")) { (input, rec) =>
    input.debug("recursive function input").isEmpty.ifThenElse(
      Remote.nil,
      Remote.bind(input.splitAt(size)) { splitPair =>
        splitPair.debug("splitPair")._1 :: rec(splitPair._2)
      }
    )
  }
```

### Metrics

Similarly to `.debug`, the `.track("name")` modifier can be used to measure the execution time and number of invocations
of a remote value. Remotes annotated like that will be reported to the metrics backend by a counter
named `zioflow_remote_evals` and a histogram named `zioflow_remote_eval_time_ms` with the given name as a tag.

## List of supported remote types

The Scala (and Java) standard library defines many useful operations on common data types like numbers, strings,
collection types, date and time related types etc. Once we wrap these types in `Remote` we loose the possibility to call
these standard library methods, as we switched into a _serializable descrition_ of a computation from defining the
actual compiled code.

ZIO Flow redefines many of these standard library functions for the remote types. As an example, consider the following
Scala code:

```scala mdoc
val scalaExample = List(4, 2, 3, 6).sorted.map(n => s"Number: $n").mkString("<", ", ", ">")
```

The equivalent ZIO Flow code would look like this:

```scala mdoc:silent
val remoteExample = 
  Remote(List(4, 2, 3, 6)).sorted.map(n => rs"Number: ${n.toString}").mkString("<", ", ", ">")
``` 

We start by wrapping the input list in `Remote`. Everything else looks completely the same, except the _string
interpolator_ which has a remote equivalent using the prefix `rs`. The remote string interpolator does not automatically
convert values to strings - so we need to explicitly call `n.toString` to convert the number to a string. Other than
that all the standard library methods required for this example are also available on remote values.

The remote `toString` method is defined on all `Remote[A]` values and it creates a `Remote[String]` as expected.

In the following tables we list the standard library methods that are available for the remote types without further
explanation - please use their original documentation for usage details.

### Remote[Boolean]

`&&`, `&` `|`, `||`, `^`, `!`

### Remote[Char],

`asDigit`,`getDirectionality`,`getNumericValue`, `getType`, `isControl`, `isDigit`, `isHighSurrogate`, `isIdentifierIgnorable`, `isLetter`, `isLetterOrDigit`, `isLowSurrogate`, `isLower`, `isMirrored`, `isSpaceChar`, `isSurrogate`, `isTitleCase`, `isUnicodeIdentifierPart`, `isUnicodeIdentifierStart`, `isUpper`, `isWhitespace`,
 `reverseBytes`, `toTitleCase`, `toUpper`

### Remote[ChronoUnit]

`getDuration`

### Remote[Chunk[A]]
`++`, `++:`, `+:`, `:++`, `appended`, `appendedAll`, `apply`, `concat`, `contains`, `containsSlice`, `corresponds`, `count`, `diff`, `distinct`, `distinctBy`, `drop`, `dropRight`, `dropWhile`, `endsWith`, `empty`, `exists`, `filter`, `filterNot`, `find`, `findLast`, `flatMap`, `flatten`, `fold`, `foldLeft`, `foldRight`, `forall`, `groupBy`, `groupMap`, `groupMapReduce`, `grouped`, `head`, `headOption`, `indexOf`, `indexOfSlice`, `indexWhere`, `init`, `inits`, `intersect`, `isDefinedAt`, `isEmpty`, `last`, `lastIndexOf`, `lastIndexOfSlice`, `lastIndexWhere`, `lastOption`, `length`, `map`, `max`, `maxBy`, `maxByOption`, `maxOption`, `min`, `minBy`, `minByOption`, `minOption`, `mkString`, `nonEmpty`, `padTo`, `partition`, `partitionMap`, `patch`, `permutations`, `prepended`, `prependedAll`, `product`, `reduce`, `reduceLeft`, `reduceLeftOption`, `reduceOption`, `reduceRight`, `reduceRightOption`, `removedAll`, `reverse`, `sameElements`, `scan`, `scanLeft`, `scanRight`, `segmentLength`, `size`, `slice`, `sliding`, `sortBy`, `sorted`, `span`, `splitAt`, `startsWith`, `sum`, `tail`, `tails`, `take`, `takeRight`, `takeWhile`, `toList`, `toMap`, `toSet`, `unzip`, `unzip3`, `zip`, `zipAll`, `zipWithIndex`

and on the `Chunk` companion object:

`fill`, `fromList`

### Remote[Duration]

`+`, `*`, `abs`, `addTo`, `dividedBy`, `get`, `getNano`, `getSeconds`, `isNegative`, `isZero`, `minus`, `minusDays`, `minusHours`, `minusMinutes`, `minusNanos`, `minusSeconds`, `multipliedBy`, `negated`, `plus`, `plusDays`, `plusHours`, `plusMinutes`, `plusNanos`, `plusSeconds`, `subtractFrom`, `toDays`, `toHours`, `toMillis`, `toMinutes`, `toNanos`, `toSeconds`, `toDaysPart`, `toHoursPart`, `toMinutesPart`, `toSecondsPart`, `toMillisPart`, `toNanosPart`, `withNanos`, `withSeconds`

and on the `Duration` companion object:

`between`, `of`, `ofDays`, `ofHours`, `ofMillis`, `ofMinutes`, `ofNanos`, `ofSeconds`, `parse`

### Remote[Either[L, R]]

`contains`, `exists`, `filterOrElse`, `flatMap`, `flatten`, `fold`, `forall`, `getOrElse`, `isLeft`, `isRight`, `joinLeft`, `joinRight`, `left.getOrElse`, `left.forall`, `left.exists`, `left.filterToOption`, `left.flatMap`, `left.map`, `left.toSeq`, `left.toOption`, `map`, `merge`, `orElse`, `swap`, `toOption`, `toSeq`, `toTry`

### Remote[Instant]

`get`, `getLong`, `getEpochSecond`, `getNano`, `isAfter`, `isBefore`, `minus`, `minusNanos`, `minusSeconds`, `minusMillis`, `plus`, `plusNanos`, `plusSeconds`, `plusMillis`, `toEpochMilli`, `truncatedTo`, `until`, `with`

and on the `Instant` companion object:

`now`, `ofEpochSecond`, `ofEpochMilli`, `parse`

### Remote[List[A]]

`++`, `++:`, `+:`, `:+`, :++`, `::`, `:::`, appended`, `appendedAll`, `apply`, `concat`, `contains`, `containsSlice`, `corresponds`, `count`, `diff`, `distinct`, `distinctBy`, `drop`, `dropRight`, `dropWhile`, `endsWith`, `empty`, `exists`, `filter`, `filterNot`, `find`, `findLast`, `flatMap`, `flatten`, `fold`, `foldLeft`, `foldRight`, `forall`, `groupBy`, `groupMap`, `groupMapReduce`, `grouped`, `head`, `headOption`, `indexOf`, `indexOfSlice`, `indexWhere`, `init`, `inits`, `intersect`, `isDefinedAt`, `isEmpty`, `last`, `lastIndexOf`, `lastIndexOfSlice`, `lastIndexWhere`, `lastOption`, `length`, `map`, `max`, `maxBy`, `maxByOption`, `maxOption`, `min`, `minBy`, `minByOption`, `minOption`, `mkString`, `nonEmpty`, `padTo`, `partition`, `partitionMap`, `patch`, `permutations`, `prepended`, `prependedAll`, `product`, `reduce`, `reduceLeft`, `reduceLeftOption`, `reduceOption`, `reduceRight`, `reduceRightOption`, `removedAll`, `reverse`, `reverse_:::`, `sameElements`, `scan`, `scanLeft`, `scanRight`, `segmentLength`, `size`, `slice`, `sliding`, `sortBy`, `sorted`, `span`, `splitAt`, `startsWith`, `sum`, `tail`, `tails`, `take`, `takeRight`, `takeWhile`, `toList`, `toMap`, `toSet`, `unzip`, `unzip3`, `zip`, `zipAll`, `zipWithIndex`

and on the `List` companion object:

`fill`

### Remote[Map[K, V]]

`+`, `++`, `-`, `--`, `apply`, `applyOrElse`, `concat`, `contains`, `corresponds`, `count`, `drop`, `dropRight`, `dropWhile`, `empty`, `exists`, `filter`, `filterNot`, `find`, `flatMap`, `fold`, `foldLeft`, `foldRight`, `forall`, `get`, `getOrElse`, `groupBy`, `groupMap`, `groupMapReduce`, `grouped`, `head`, `headOption`, `init`, `inits`, `intersect`, `isDefinedAt`, `isEmpty`, `keySet`, `keys`, `last`, `lastOption`, `lift`, `map`, `mkString`, `nonEmpty`, `partition`, `partitionMap`, `reduce`, `reduceLeft`, `reduceLeftOption`, `reduceOption`, `reduceRight`, `reduceRightOption`, `removed`, `removedAll`, `scan`, `scanLeft`, `scanRight`, `size`, `slice`, `sliding`, `span`, `splitAt`, `tail`, `tails`, `take`, `takeRight`, `updated`, `toList`, `toSet`, `unzip`, `values`, `zip`, `zipAll`

### Remote[OffsetDateTime]

`getYear`, `getMonthValue`, `getDayOfMonth`, `getHour`, `getMinute`, `getSecond`, `getNano, `getOffset`, `toInstant`

and on the `OffsetDateTime` companion object:

`of`, `ofInstant`

### Remote[Option[A]]

`contains`, `exists`, `filter`, `filterNot`, `flatMap`, `fold`, `foldLeft`, `foldRight`, `forall`, `get`, `getOrElse`, `head`, `headOption`, `isSome`, `isNone`, `isDefined`, `isEmpty`, `knownSize`, `last`, `lastOption`, `map`, `nonEmpty`, `orElse`, `toLeft`, `toList`, `toRight`, `zip` 

### Remote[Regex]

`findFirstIn`, `findMatches`, `matches`, `replaceAllIn`, `replaceFirstIn`, `split`, `regex`

### Remote[Set[A]]
`&`, `&~`, `+`, `++`, `-`, `--`, `apply`, `concat`, `contains`,`corresponds`, `count`, `diff`, `drop`, `dropRight`, `dropWhile`, `empty`, `excl`, `exists`, `filter`, `filterNot`, `find`, `flatMap`, `flatten`, `fold`, `foldLeft`, `foldRight`, `forall`, `groupBy`, `groupMap`, `groupMapReduce`, `head`, `headOption`, `init`, `inits`, `intersect`, `isEmpty`, `last`, `lastOption`, `map`, `max`, `maxBy`, `maxByOption`, `maxOption`, `min`, `minBy`, `minByOption`, `minOption`, `mkString`, `nonEmpty`, , `partition`, `partitionMap`, `product`, `reduce`, `reduceLeft`, `reduceLeftOption`, `reduceOption`, `reduceRight`, `reduceRightOption`, `removedAll`,, `sameElements`, `scan`, `scanLeft`, `scanRight`, `size`, `slice`, `sliding`, `span`, `splitAt`, `sum`, `tail`, `tails`, `take`, `takeRight`, `takeWhile`, `toList`, `toMap`, `toSet`, `union`, `unzip`, `unzip3`, `zip`, `zipAll`, `zipWithIndex`, `|`

### Remote[String]
`*`, `+`, `++`, `++:`, `+:`, `:+`, `:++`, `appended`, `appendedAll`, `apply`, `capitalize`, `charAt`, `concat`, `contains`, `count`, `diff`, `distrinct`, `distinctBy`, `drop`, `dropRight`, `dropWhile`, `endsWith`, `exists`, `filter`, `filterNot`, `find`, `flatMap`, `fold`, `foldLeft`, `foldRight`, `forall`, `groupBy`, `groupMap`, `groupMapReduce` `grouped`, `head`, `headOption`, `inddexOf`, `indexWhere`, `init`, `inits`, `intersect`, `isEmpty`, `knownSize`, `last`, `lastIndexOf`, `lastIndexWhere`, `lastOption`, `length`, `map`, `mkString`, `nonEmpty`, `padTo`, `partition`, `partitionMap`, `patch`, `permutations`, `prepended`, `prependedAll`, `r`, `replace`, `replaceAll`, `replaceFirst`, `reverse`, `size`, `slice`, `sliding`, `span`, `split`, `splitAt`, `startsWith`, `strip`, `stripLeading`, `stripLineEnd`, `stripMargin`, `stripPrefix`, `stripTrailing`, `stripSuffix`, `substring`, `tail`, `tails`, `take`, `takeRight`, `takeWhile`, `toBase64`, `toBoolean`, `toBooleanOption`, `toByte`, toByteOption`, `toDouble`, `toDoubleOption`, `toFloat`, `toFloatOption`, `toInt`, `toIntOption`, `toList`, `toLong`, `toLongOption`, `toLowerCase`, `toShort`, `toShortOption`, `toUpperCase`, `trim` 

### Remote numeric types
Applies to `Char`, `Byte`, `Short`, `Int`, `Long`, `Float`, `Double`, `BigDecimal`, `BigInt`

`+`, `/`, `%`, `*`, `unary_-`, `-`, `abs`, `sign`, `min`, `max`, `toInt`, `toChar`, `toByte`, `toShort`, `toLong`, `toFloat`, `toDouble`, `intValue`, `shortValue`, `longValue`, `floatValue`, `doubleValue` 

### Remote integral types
Applies to `Char`, `Byte`, `Short`, `Int`, `Long`, `BigInt`

`>>`, `<<`, `>>>`, `&`, `|`, `^`, `toBinaryString`, `toHexString`, `toOctalString`

### Remote fractional types
Applies to `Double`, `Float` and `BigDecimal` remotes.

`floor`, `ceil`, `round`, `toRadians`, `toDegrees`, `isNaN`, `isInifinity, `isInfinite`, `isFinite`, `isPosInfinity`, `isNegInfinity`

### Remote tuples
The remote tuples have accessor fields generated with the usual `_1`, `_2`, ... `_22` names.

### Remote[_]

`<`, `<=`, `>`, `>=`, `===`, `!==`, `toString` 

### math object
The `zio.flow.remote.math` object contains Remote equivalents of the standard Scala `math` package:

`sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `toRadians`, `toDegrees`, `atan2`, `hypot`, `ceil`, `floor`, `rint`, `round`, `abs`, `max`, `min`, `signum`, `floorDiv`, `floorMod`, `copySign`, `nextAfter`, `nextUp`, `nextDown`, `scalb`, `sqrt`, `cbrt`, `pow`, `exp`, `expm1`, `getExponent`, `log`, `log1p`, `log10`, `sinh`, `cosh`, `tanh`, `ulp`, `IEEEremainder`, `addExact`, `subtractExact`, `multiplyExact`, `incrementExact`, `decrementExact`, `negateExact`