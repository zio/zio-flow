---
id: zflow
title: "Defining flows"
---

# Defining flows

## Overview

ZIO Flow is based on defining executable workflows as values of the type `ZFlow[R, E, A]`. This type is similar
to `ZIO[R, E, A]` in
that it represents a program as a value that can fail with the type `E` or succeed with the type `A`. The most important
difference is
that a `ZFlow` value is _serializable_, which means that it can be sent over the network for a remote _executor_.

When working with `ZFlow` programs, another core concept of ZIO Flow is _remote values_.
There [is a separate section](remote.md) about working
with remote values and remote functions. In this section we will focus on how to define flows and we can assume that
remote values and functions
work similarly to regular Scala values and functions.

## Flow control

The basic building blocks of defining a `ZFlow` workflow are similar to `ZIO`. We can use `succeed` or `fail` to create
a flow that finishes
with a result immediately:

```scala mdoc
import zio.{ZNothing, durationInt}
import zio.flow._

val flow1 = ZFlow.succeed(100)
val flow2 = ZFlow.fail("Failed")
```

Note that the above code does not run anything; it just defines workflows as serializable values of the type `ZFlow`
that can be later sent
to an executor. In the future code examples we will not show the evaluated `ZFlow` values as they can be very verbose.

The usual `map`, `flatMap`, `as`, and variants of `zip` are all available on `ZFlow` so we can use _for comprehensions_
to define them:

```scala mdoc:silent
val flow3 = 
    for {
        a <- ZFlow.succeed(100)
        b <- ZFlow.succeed(200)
    } yield a + b
```

To handle failures we have `catchAll`, `orElse`, `foldFlow` and `ensuring`:

```scala mdoc:silent
val flow4 = ZFlow.fail("Failed").ensuring(ZFlow.log("Flow 4 finished"))
val flow5 = ZFlow.fail("Failed").orElse(ZFlow.succeed(1))
val flow6 =
  ZFlow
    .fail("Failed")
    .catchAll { (failure: Remote[String]) => 
      ZFlow.log(rs"Failed with $failure") *> ZFlow.succeed(1) 
    }
```

## Logging

The simplest way to interact with the outside world from a ZIO Flow program is to log a message:

```scala mdoc:silent
val flow7 = ZFlow.log("Hello world")
```

The logged message goes to the log output of the executor tagged by the running flow's identifier.

## Variables

Persistent variables are named mutable slots belonging to a specific flow. They can be used to communicate between
different,
possibly parallel steps of a flow, and to expose some observable flow state for debugging purposes. They are similar to
the `Ref` data type in `ZIO`.

```scala mdoc:silent
val flow8 = 
  for {
    var1   <- ZFlow.newVar("var1", 100)
    _      <- var1.update(_ + 1)
    result <- var1.get
  } yield result
```

## Iteration and recursion

There are multiple ways to repeat the execution of a flow.

The simplest one is `replicate` that repeats the flow a given number of times:

```scala mdoc:silent
val flow9 = ZFlow.log("hello").replicate(10)
```

A more complex way is using `iterate` which allows you to define a _step function_ and a _condition_
and repeats the flow until the condition becomes true. The initial value is the flow's result itself:

```scala mdoc:silent
val flow10 = ZFlow.log("hello").as(1).iterate((x: Remote[Int]) => x + 1)(_ === 10) 
```

The result of the flow will be the final value of the iterated variable, in this case `10`. Note the use of the triple
equal operator (`===`) instead of the usual double (`==`). This is a limitation of the _remote types_,
covered [in the next section](remote).

Another two operators on flows are `recurseSimple` and `recurse`. Why can't we just write recursive Scala functions as
usual?
Let's take the following example:

```scala
def recursiveFlow1(n: Remote[Int]): ZFlow[Any, Nothing, Int] =
  ZFlow.ifThenElse(n === 10)(
    ifTrue = ZFlow.succeed(10),
    ifFalse = recursiveFlow1(n + 1).map(_ + 1)
  )
val flow11Bad = recursiveFlow1(0)
```

Note that we cannot use Scala `if () {} else {}` expressions because they are not serializable. The `ifThenElse` method
defined on `Remote[Boolean]`
is an alternative of that, explained [in the next section](remote).
Even though the above code compiles, it is not serializable! ZIO Flow has no way to detect that the call
to `recursiveFlow1` is a recursion point
and it would end up in a stack overflow.

The same example can be safely implemented by using the `recurseSimple` operator:

```scala mdoc:silent
val flow11Good = 
  ZFlow.recurseSimple[Any, ZNothing, Int](0) { case (value, rec) =>
    ZFlow.ifThenElse(value === 10)(
      ifTrue = ZFlow.succeed(value),
      ifFalse = rec(value + 1)
    )
  }
```

`recurse` is a slightly more complicated variant that allows the recursion body to have a different result type than the
initial value.
This usually requires specifying some type parameters by hand, so in simple cases `recurseSimple` leads to cleaner code.

ZIO Flow also has `foreach` and `foreachPar` to execute a flow for each element of a list:

```scala mdoc:silent
val flow12 = ZFlow.foreach(List(1, 2, 3)) { x => ZFlow.log(rs"${x.toString}") }
val flow13 = ZFlow.foreachPar(List(1, 2, 3)) { x => ZFlow.log(rs"${x.toString}") }
```

## Fibers

ZIO Flow programs can fork _fibers_ for parallel execution, similar to how `ZIO` programs does it. The `fork` operator
returns
a value of `ExecutingFlow[E, A]` which can be used to await or interrupt the execution of the child fiber.

```scala mdoc:silent
val flow14 =
  for {
    fiber1 <- ZFlow.sleep(5.minutes).fork
    fiber2 <- ZFlow.sleep(1.hour).fork
    _      <- fiber2.interrupt
    _      <- fiber2.await
  } yield ()
```

It is often useful to _timeout_ after a given duration, for example when waiting for a fiber to finish. This can be done
using the `timeout` operator:

```scala mdoc:silent
val flow15 = ZFlow.sleep(1.hour).timeout(10.seconds)
```

## The external world

There are other ways for ZIO Flow programs to communicate with the external world than just logging messages. The main
categories are:

- Getting an input for the execution of the flow
- Accessing some built-in services such as time and random generator
- Accessing external services

### Input

ZIO Flow programs has a third type parameter, `R`, which was not used in the above examples. This represents the _input_
of the flow. When sending
a flow to an executor, if the R type is not `Any`, you also have to provide an input value of type `R` as a _parameter
of the execution_.

To access this value from the flow, we can use the `input` operator:

```scala mdoc
val flow16 = ZFlow.input[Int]
```

Note that the result type is now `ZFlow[Int, ZNothing, Int]`. The flow will _read the input_, and use it as its result.

When composing flows, you may want to eliminate the input requirement of a sub-flow. This can be done using
the `provide` operator:

```scala mdoc
val flow17 = ZFlow.input[Int].provide(100)
```

The result type is now `ZFlow[Any, ZNothing, Int]`. This flow has no input requirement, and will always return `100`.

### Time

Getting the current time is an important operation for ZIO Flow workflows because it allows time based schedules,
generating timestamps or expiration times, etc.

To get the current time as a remote value of `Instant`, use:

```scala mdoc:silent
val flow18 = ZFlow.now
```

### Random

The flow may need to generate random values like random numbers, UUIDs etc. The `ZFlow` type has two random related
operators:

```scala mdoc:silent
val flow19 = ZFlow.random.map(double => rs"Random double: ${double.toString}")
val flow20 = ZFlow.randomUUID.map(uuid => rs"Random UUID: ${uuid.toString}")
```

There are higher level random functions exposed on `zio.flow.Random`, reflecting the ZIO `Random` service's
functionalities:

```scala mdoc:silent
val flow21 = Random.nextIntBetween(10, 100)
val flow22 = Random.nextString(16)
```

### Activities

The primary way to communicate with the external world is by using _activities_. An activity is defined by the
type `Activity` and
it is a description of how to call an external service and how it should behave when it is used in _transactions_.
The [activities](activities.md)
section explains in detail how to define and use activities.

## Scheduling

We have seen that ZIO Flow programs can access the current time, and ways to repeat the execution of a part of the flow.
Often we want to
either delay the execution of something or repeat it at a given interval.

The two most simple operators are `sleep` and `waitTill`. Sleep will delay the execution of the flow for a given
duration:

```scala mdoc:silent
val flow23 = ZFlow.sleep(10.seconds)
```

The `waitTill` operator will delay the execution of the flow until a given instant:

```scala mdoc:silent
val flow24 = ZFlow.waitTill(Instant.parse("2022-12-12T10:00:00Z"))
```

ZIO Flow also defines a data type for describing more complex schedules. This type is `ZFlowSchedule` and it can be used
as a parameter to
the `schedule` and `repeat` opeators on `ZFlow`:

- `schedule` will execute the flow once, according to the given schedule
- `repeat` will execute the flow immeditely and then repeat it according to the given schedule

The following example logs a message every second:

```scala mdoc:silent
val flow25 = ZFlow.log("hello").repeat(ZFlowSchedule.fixed(1.second))
```

Other than fixed there are some other schedule contructors for defining hourly, daily or monthly schedules:

```scala mdoc:silent
val flow26 = ZFlow.log("hello").repeat(ZFlowSchedule.everyHourAt(11, 0))
val flow27 = ZFlow.log("hello").repeat(ZFlowSchedule.everyDayAt(15, 30, 0))
val flow28 = ZFlow.log("hello").repeat(ZFlowSchedule.everyMonthAt(1, 12, 0, 0))
```

It is possible to define more complex schedules by using the `or` (or `|`) operator. The following example will log a
message every hour twice:

```scala mdoc:silent
val flow29 = ZFlow.log("hello").repeat(
  ZFlowSchedule.everyHourAt(11, 0) | ZFlowSchedule.everyHourAt(44, 30) 
)
```

To limit the number of repetions of a schedule, use `maxCount`:

```scala mdoc:silent
val flow30 = ZFlow.log("hello").repeat(
  ZFlowSchedule.fixed(1.second).maxCount(10)
)
```

## Transactions

ZIO Flow programs can modify variables and perform _activities_ in a _transactional way_. When transactions are used in
a single fiber, it guarantees that in case of failure all the undoable activities performed within the transactions are
rolled back.

To learn more about how activities can be rolled back [check the activities page](activities).

When using transactions in parallel running ZFlow fibers, the transactions are also tracking the usage of remote variables.
If two transactional fibers are modifying the same variable, one of them will _retry_ to make sure the whole transaction remains
consistent.

The following simple example shows how the traditional problem of parallel modification of a mutable variable can be solved using ZIO Flow transactions:

```scala mdoc:silent
val flow31 =
  for {
    var1 <- ZFlow.newVar[Int]("var1", 10)
    now  <- ZFlow.now
    fib1 <- ZFlow.transaction { _ =>
      for {
        v1 <- var1.get
        _ <- var1.set(v1 + 1)
      } yield ()
    }.fork
    fib2 <- ZFlow.transaction { _ =>
      for {
        v1 <- var1.get
        _ <- var1.set(v1 + 1)
      } yield ()
    }.fork
    _  <- fib1.await
    _  <- fib2.await
    result <- var1.get
  } yield result
```

This will always return `12` because in case of conflict one of the transactions will be retried.

It is also possible to _conditionally retry_ a transaction. By passing a remote boolean expression, the transaction will
evaluate the condition and in case it is `false` it will undo everything the transaction did and retry it. It also _suspends_
the execution of the retried transactions until any of the variables used in the transaction up to the evaluation of the condition
changes. This way it avoids repeated evaluation and failure of the flow.

The following example will retry the transaction two times:

```scala mdoc:silent
val flow32 =
  for {
    variable <- ZFlow.newVar("var", 0)
    fiber <- ZFlow.transaction { tx =>
               for {
                 value <- variable.get
                 _     <- ZFlow.log("Transaction executed")
                 _     <- tx.retryUntil(value === 2)
               } yield value
             }.fork
    _      <- ZFlow.sleep(1.second)
    _      <- variable.set(1)
    _      <- ZFlow.sleep(1.second)
    _      <- variable.set(2)
    _      <- ZFlow.log("Waiting for the transaction fiber to stop")
    result <- fiber.await
  } yield result
```