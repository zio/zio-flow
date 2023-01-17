---
id: index
title: "Introduction to ZIO Flow"
sidebar_label: "ZIO Flow"
---

ZIO Flow is an engine for executing persistent, distributed, fault-tolerant applications, providing an easy and powerful way to build stateful serverless applications.

@PROJECT_BADGES@

ZIO Flow helps you orchestrate complex business logic, without having to worry about fallible systems, transient failures, manual rollbacks, or other infrastructure.

- Type-safe, compositional front-end
- Resilient interactions with databases, web services, and microservices
- Persistent workflows that survive restarts
- Transactional guarantees via a persistent saga pattern

## Getting started

For defining ZIO Flow programs, you need to add the following dependency to your `build.sbt` file:

```scala
libraryDependencies += "dev.zio" %% "zio-flow" % "@VERSION@"
```

This module is available for Scala.js as well.

To learn more about defining ZIO Flow programs, see [Defining flows](zflow). 

There are a couple of _activity libraries_ providing integration with 3rd party services. These libraries are available as separate modules, 
and they only have to be present on the definition side of your flows. The executor does not need to have these libraries on the classpath.

```scala
libraryDependencies += "dev.zio" %% "zio-flow-twilio" % "@VERSION@"
libraryDependencies += "dev.zio" %% "zio-flow-sendgrid" % "@VERSION@"
```

There are many ways to execute ZIO Flow programs. The easiest way is to use a compiled version of the built-in _ZIO Flow Server_:

```scala
sbt zioFlowServer/run
```

We will provide ready to use Docker images as well in the future.

To embed the ZIO Flow executor in your own application, you need to add the following dependency:

```scala
libraryDependencies += "dev.zio" %% "zio-flow-runtime" % "@VERSION@"
```

For more information about the executors, see [Execution](execution). 
You will also need to choose a [persistent backend implementation](backends):

```scala
libraryDependencies += "dev.zio" %% "zio-flow-rocksdb" % "@VERSION@"
libraryDependencies += "dev.zio" %% "zio-flow-dynamodb" % "@VERSION@"
libraryDependencies += "dev.zio" %% "zio-flow-cassandra" % "@VERSION@"
```
