package zio.flow.examples

import zio._
import zio.flow._
import zio.flow.activities.sendgrid._
import zio.flow.activities.twilio._
import zio.schema.Schema

object SendMailAndSMS extends ZIOAppDefault {
  // NOTE: requires increased stack size (-Xss16m works)

  override val bootstrap =
    Runtime.removeDefaultLoggers ++ Runtime.addLogger(
      ZLogger.default.map(println)
    )

  override def run: ZIO[ZIOAppArgs with Scope, Any, Any] =
    for {
      result <- runFlow(flow)
                  .provide(
                    Configuration.fromEnvironment(
                      sendgridApiKey   -> "SENDGRID_API_KEY",
                      twilioAccountSid -> "TWILIO_ACCOUNT_SID",
                      twilioAuthToken  -> "TWILIO_AUTH_TOKEN"
                    ),
                    ZFlowExecutor.defaultInMemoryJson
                  )
      _ <- Console.printLine(s"Result: $result")
    } yield ()

  private def runFlow[R, E: Schema, A: Schema](flow: ZFlow[Any, E, A]): ZIO[ZFlowExecutor, E, A] =
    FlowId.newRandom.flatMap { id =>
      ZFlowExecutor.submit(id, flow)
    }

  private def sendExampleEmail: ZFlow[Any, ActivityError, String] =
    for {
      _ <- ZFlow.log("Sending example e-mail")
      _ <- sendMail(
             Mail(
               personalizations = List(
                 Personalization(
                   to = List(EmailAddress("TODO", "TODO"))
                 )
               ),
               from = EmailAddress("TODO", "TODO"),
               subject = "Test e-mail from zio-flow",
               content = List(
                 Content(
                   `type` = "text/html",
                   value = "<p>This e-mail was sent from a zio-flow program!</p>"
                 )
               )
             )
           )
    } yield "email sent"

  private def sendExampleSMS(message: Remote[String], at: Remote[Option[Instant]]): ZFlow[Any, ActivityError, String] =
    for {
      _ <- ZFlow.log(rs"Sending example SMS with message $message at ${at.toString}")
      createMessage <-
        CreateMessage.sendAt.set(
          CreateMessage.body.set(
            CreateMessage(
              From = Some(PhoneNumber("TODO")),
              To = PhoneNumber("TODO"),
              Body = "",
              ScheduleType = Some(MessageScheduleType.fixed),
              MessagingServiceSid = Some(MessagingServiceSid("TODO"))
            )
          )(message)
        )(at)
      _ <- sendSMS(createMessage)
    } yield "sms sent"

  private val flow =
    for {
      _    <- ZFlow.log("SendMailAndSMS example started")
      fib1 <- sendExampleEmail.fork
      fib2 <- sendExampleSMS("hello from zio-flow!", Remote.none).fork // Send immediately
      now <- ZFlow.now
      fib3 <- ZFlow.transaction { _ =>
                for {
                  result <- sendExampleSMS(
                              "this will be canceled",
                              Remote.some(now.plus(5.minutes))
                            ) // Scheduled send, then cancel by failing the transaction
                  _ <- ZFlow.sleep(5.seconds)
                  _ <- ZFlow.fail(ActivityError("fail the transaction!", None))
                } yield result
              }.fork
      results <- (fib1.await <*> fib2.await <*> fib3.await)
    } yield results
}
