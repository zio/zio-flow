package zio.flow

/*

agentApproves:       Variable[Boolean]
userAgrees:          Variable[Boolean]
underwriterApproves: Variable[Boolean]

val agentWorkflow =
  ZFlow.transaction { txn =>
    for {
      approves <- agentApproves.get
      _        <- txn.retryUntil(approves == true)
      _        <- sendEmails(...)
    } yield ()
  }

val userWorkflow =
  ZFlow.transaction { txn =>
    for {
      approves <- userAgrees.get
      _        <- txn.retryUntil(approves == true)
      _        <- sendEmails(...)
    } yield ()
  }

val underwriterWorkflow =
  ZFlow.transaction { txn =>
    for {
      approves <- underwriterApproves.get
      _        <- txn.retryUntil(approves == true)
      _        <- sendEmails(...)
    } yield ()
  }

  val allDone =
    for {
      done1 <- underwriterApproves.get
      done2 <- userAgrees.get
      done3 <- agentApproves.get
    } yield done1 && done2 && done3


  ZFlow.agentWorkflow orTry userWorkflow orTry underwriterWorkflow

  ZFlow.doUntil {
    (ZFlow.agentWorkflow orTry userWorkflow orTry underwriterWorkflow) *> allDone
  }
 */
trait Variable[A] {
  self =>
  def get: ZFlow[Any, Nothing, A] = modify(a => (a, a))

  def set(a: Remote[A]): ZFlow[Any, Nothing, Unit] =
    modify[Unit](_ => ((), a))

  def modify[B](f: Remote[A] => (Remote[B], Remote[A])): ZFlow[Any, Nothing, B] =
    ZFlow.Modify(self, (e: Remote[A]) => Remote.tuple2(f(e)))

  def updateAndGet(f: Remote[A] => Remote[A]): ZFlow[Any, Nothing, A] =
    modify { a =>
      val a2 = f(a)
      (a2, a2)
    }

  def update(f: Remote[A] => Remote[A]): ZFlow[Any, Nothing, Unit] = updateAndGet(f).unit

  def waitUntil(predicate: Remote[A] => Remote[Boolean]): ZFlow[Any, Nothing, Any] = ZFlow.transaction { txn =>
    for {
      v <- self.get
      _ <- txn.retryUntil(predicate(v))
    } yield ()
  }
}
