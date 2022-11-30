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

```scala mdoc:silent
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

```scala mdoc:silent
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

Let's see what happens when we evaluate this:

- First we evaluate the _parameter value_ and get a `DynamicValue` back
- Then we generate a _fresh_ `RemoteVariableName`, a new ID that is guaranteed that was not used before
- We store the parameter value in a _remote variable_ with this new ID. When this evaluation is part of a persistent
  flow execution, in this case the _remote context_ is not persisting the remote variable yet. It is just storing it in
  memory.
- We push a _binding_ in the _local context_. The local context belongs to a single remote evaluation, while the remote
  context for a whole persistent execution step. By pushing the local binding to the local context we are able to
  evaluate the `Remote.Unbound` nodes.
- We evaluate the `Bind`'s inner remote, which is the `UnboundRemoteFunction`. This is a remote expression tree which
  has `Remote.Unbound` in it, which we can now evaluate to the evaluated input because it is stored in the local
  context.
- After that we remove the binding from the local context.

At this point we have a result of the applied function, but we are not done yet. It is possible that the _result_ of the
function is a remote that captures the original `Unbound` remote node, but the binding is only active during evaluating
this particular remote `Bind`. So that would lead into an invalid continuation. To help imagine this situation, consider
this very simple and unusual example:

```scala mdoc
val f4 = (x: Remote[Int]) => ZFlow.succeed(x)
```

This could be a function passed to a `flatMap` in a ZIO Flow program.

When we evaluate the function we just get back a new `ZFlow` value, that refers to a `Remote.Unbound` node (by using `x`
in `succeed`). But the binding is only active during the actual function evaluation. So the solution ZIO Flow has is
that once we evaluated a function, we check if the _result_ refers to the input `Unbound` identifier. All `ZFlow`
and `Remote` nodes are tracking their used variables so this does not require traversing the whole continuation. If it
does not contain it, we are done. Otherwise we _substitute_ the `Unbound` node in the result tree to the _remote
variable_ we generated. This way we moved out the local binding to be a persisted binding, as remote variables are
persisted; now it can be accessed in the continuation safely, even if the executor restarts.

### What is implemented as a Remote and what not?

There are many `Remote` constructors for some primitive operations we support, but there are even more functionalities
implemented in other classes, such as `BinaryOperators`, `RemoteConversions`, etc. The primary distinction is that if
something can be implemented by staying on the level of `DynamicValue`s, it is a `Remote` constructor. If the
calculation requires converting the dynamic values to some typed value first, it is implemented in one of the supporting
classes such as the ones mentioned above.

## Persistent variables and promises

From the ZIO Flow program's perspective a persistent variables has a name typed as `RemoteVariableName`, which is just a
wrapper over `String`. The name used to store the variable in the _key-value store_ is derived from this name but
contains more information.

Each variable is defined in a given [scope](#scoping-rules). In short, the scope identifies the flow/fiber/transaction
the variable was defined in. When accessing a remote variable there are scoping rules (defined below) defining how
variables can be accessed from parent scopes.

The actual variable name used for storing the variable is described by `ScopedRemoteVariableName`, which associates
a `RemoteVariableScope` with a `RemoteVariableName`. The _key_ has to be invertible
for [garbage collection](#garbage-collection) to be able to identify all the stored variables.

The persistent executor is not storing the variables directly using the `KeyValueStore` interface, but uses a wrapper on
top of it called `RemoteVariableKeyValueStore`. This wrapper is responsible for dealing with the scoping rules,
publishing change events for watched variables, and to handle _timestamps_.

Each variable has a timestamp. Setting a new value for a remote variable does not overwrite the old value, but instead
writes a new value with a new timestamp. Timestamps are essential for [transactions](#transactions) to be able to detect
conflicts in accessing the variables. The Timestamp values are coming from a _virtual clock_ which is basically
incrementing at each flow step. Forked flows inherit the current parent timestamp. In case a forked flow is joined, the
parent fiber's virtual clock will be advanced to the maximum of the parent and the child fiber's clock.

## Executor state management

The persistent executor persists its state and any new/changed persisted remote variables after each _step_. One step is
the processing of one `ZFlow` instruction.

While processing the step the executor collects a sequence of `StateChange` values. At the end of each execution step
the following major steps are performed:

- applying the `StateChange` changes to the executor's state (in memory)
- collecting the remote variables which were accessed (read or modified) during the step
- saving the modified persistent variables to the key-value store
- applying some more `StateChange` values to the executor's state, like recording the modified variables and advancing
  the virtual clock
- persisting the new executor's state to the key-value store

Currently after each step we save a the full executor state into the key-value store. This is not optimal, but the
executor is designed in a way by working with `StateChange` values that in the future it is going to support saving only
the changes into a journal instead.

## Transactions

In transactions every time a variable is accessed, it's current timestamp gets recorded. When the transaction is
committed, these timestamps are compared to the actual timestamps and in case there is a difference that means there is
a conflict and the transaction has to be retried.

Retry can also be triggered by the `retryUntil` operator - it is implemented as special kind of failure. In fact within
a
transaction each user error `E` is wrapped in a `TransactionalFailure[E] = UserError[E] | Retry` type.

Retry can be captured by the `orTry` operator in which case it works exactly like handling an error with `Fold`.

In case the retry is not handled when it reaches a `CommitTransaction` instruction in the stack, the transaction gets
restarted.

Retrying or failing in a transaction also causes all the _activities_ to get compensated by running their compensate
flows in reverse order.

## Scoping rules

### Workflows

A top level workflow defines the top level scope by its unique flow identifier. This guarantees that:

- separate workflows cannot see each other's variables
- restarted workflows see the same set of variables as the previous run as
  they share the flow id

Operation semantics on the top level of workflows (not in a forked fiber and
not in an active transaction):

- New(name): creates a new remote variable in the KV store's variable
  namespace called `"$flowid__$name"`
- Get(name): reads `"$flowid__$name"`
- Set(name): writes `"$flowid__$name"`

### Fibers

Forked workflows are like regular workflows but they are not individually submitted, instead created by the executor by
the Fork operator. Each workflow maintains a fork counter and generates new workflow ids based
on that. So a forked workflow's flow identifier will be
`"$parentId_fork$parentForkCounter"`.

Desired semantics:

- Forked workflows should have read/write access to variables accessible to
  the parent workflow
- Creating new variables in a forked workflow should not be accessible to
  the parent and sibling workflows
- Parallel forked workflows should be able to create independent variables
  with the same name

Operation semantics in forked workflows:

- New(name): creates a new remote variable in the KV store's variable
  namespace prefixed by the active workflow identifier `"$flowid__$name"`
  (which is `"$parentId_fork$parentForkCounter__$$name"`).
- Get(name): first finds the variable's scope by first looking in the
  current fiber's scope (using `"$flowid__$name"`) - if it does not
  exist, it recursively tries to access the variable in the parent scope
  (`"$parentid__$name"`).
- Set(name): same lookup as for Get - Get and Set must always select the
  same variable in an executor step

### Transactions

In transactions we have to delay the effect of Set (but within the
transaction still see that value in Get) until the transaction is committed.
This means that we need to store values for the same remote variable name per
transaction beside its original value - which means transactions define their
own scope.

Desired semantics:

- Creating a new variable in a transaction: should not behave differently
  than in a regular scope
    - transactional variable updates are only interesting if there are
      multiple fibers running transactions modifying the same variable. This
      means that even if there are "colliding" new variables in parallel
      transactions, their parent scope will be different (because fibers are
      also defining scopes) so they would never collide.
- Within the transaction, Get and Set should work as usual, but the effect
  of Set should not be visible for other fibers, even if the changed
  variable is in a shared scope.
- When the transaction is committed, the changes are either applied to
  these shared variables, or the transaction gets reverted.

Flow state contains a transaction counter that can be used as a unique
identifier for transaction scopes, similar to how fiber scopes are generated:
`"parentId_tx$transactionCounter"`.

Operation semantics in transaction scopes:

- New(name): creates a new remote variable in the parent scope
- Get(name): acts the same way as in forked workflows, but also records the
  accessed variable's version if necessary
- Set(name): always sets the value in the transaction scope
  (`$parentid__$name`)

## Garbage collection

The garbage collector of the persisted executor runs periodically and performs the following steps:

First we get all the persisted remote variables from the key-value store. The encoding of scopes in the variable names
is invertible so we can recover a set of scoped remote variable names by scanning the keys.

For each running flow we gather the known set of remote variables referenced by the remaining of that flow. For this we
get each flow's state and get the referenced remote variables from it's current stack (variable usage is already tracked
on ZFlow and Remote level).

Because of how variable [scoping](#scoping-rules) works, we don't know in the GC in advance exactly which scoped remote
variable a given
remote variable name refers to - it is possible that a fiber refers to its parent fiber's remote variable, etc.

Transactions are also generating scoped variables. To work around this the garbage collector is following a pessimistic
but safe logic: if a flow refers to variable A, it prevents the removal of A from that flow and all its parent flows.
For
variables with transactional scope, we know them from the list of all existing scoped remote variables, and we simply
keep all of them belonging to the flow where the referenced name is coming from. So if flow X references variable A and
in the key-value store we have two variables with name A in scope `X/transaction1` and `X/transaction2` we simply keep
both.
