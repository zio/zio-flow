---
id: activities
title: "Activities"
---

# Activities

## Overview
Activities describe how to communicate with the external world from a ZIO Flow program, and they provide special support to be used from within _transactions_. Let's see a real world example of defining an activity:

```scala mdoc:silent
import zio.flow._
import zio.flow.operation.http._

type CreateMessage = String // Real type not shown here
type Message = String       // Real type not shown here

val twilioAccountSid: ConfigKey = ConfigKey("authentication.twilio.account_sid")
val twilioAuthToken: ConfigKey  = ConfigKey("authentication.twilio.auth_token")

private lazy val twilioAuthHeader: Remote[String] =
  rs"Basic " + rs"${Remote.config[String](twilioAccountSid)}:${Remote.config[String](twilioAuthToken)}".toBase64

lazy val sendSMS: Activity[CreateMessage, Message] =
  Activity(
    "twilio_sendSMS",
    "Sends an SMS using a Twilio account",
    operation = Operation.Http(
      host = "https://api.twilio.com",
      API
        .post("2010-04-01" / "Accounts" / string / "Messages.json")
        .header(Header.string("Authorization"))
        .input[CreateMessage](ContentType.`x-www-form-urlencoded`)
        .output[Message]
    ),
    check = Activity.checkNotSupported,
    compensate = Activity.compensateNotSupported
  ).contramap[CreateMessage] { (createMessage: Remote[CreateMessage]) =>
    (
      Remote.config[String](twilioAccountSid),
      twilioAuthHeader,
      createMessage
    )
  }
```

This is a slightly simplified version of an _activity_ that uses Twilio as a third party provider for sending SMS. 
Let's go through this definition line by line!

- First we need an _input type_ for our activity - in this case it is called `CreateMessage` and in a real implementation it would be a case class with many fields but for simplicity we just define it as a `String` here.
- When the activity succeeds it also returns a value - for this we define the type `Message`, omitting the details for it as well, representing an SMS that has been sent.
- The next two lines defines two `ConfigKey`s for the Twilio account SID and auth token. These are used to authenticate with Twilio. For more information about accessing configuration from ZIO Flow programs see [the remotes section](remote#accessing-configuration).
- `twilioAuthHeader` uses the _remote string interpolator_ feature to define a remote value which represents the _HTTP basic auth header_ to be sent to Twilio. It's the concatenation of the accound ID and the token converted to Base64.
- The `sendSMS` value defines the activity itself. Every activity has an _input_ and an _output_ type.
- The first parameter defines a unique name for the activity, seen in logs, etc.
- The second parameter is a textual description of what the activity does; it is not used currently but later could appear in user interfaces built around ZIO Flow.
- The `operation` parameter defines how to perform the activity. In this case we use the `API` helper to define an operation that calls an _HTTP endpoint_. This is imported from `zio.flow.operation.http`. More information about how to define HTTP requests can be found in the [next section](activities#http).
- The `check` and `compensate` parameters are set to default values indicating that this functionality is not supported.

An activity like the one above can do some external work but in case it is used in a [_transaction_](zflow#transactions) it is important to be _idempotent_. The reason is that a transaction's body may run multiple times in case it detects some conflicts in the used variables; Without defining the `check` and/or `compensate` parameters of the activity ZIO Flow has no way to undo an activity like this - in the above example, the SMS will be sent as many times as the transaction retries, and nothing will happen with sent SMS in case the transaction fails.

The `check` parameter can be set to a `ZFlow` that checks if the activity has already been performed. If it has, it returns the activity's expected output, and the _operation_ will not be performed. This is useful when a ZIO Flow program was restarted possibly due to a hardware failure or deployment, while it was in the middle of performing an _activity_. In this case the persisted state may indicate that the activity has not been performed yet, but maybe it was already initiated; if there is no `check` flow, the operation will be performed again - if we can `check` if it has been performed or not, duplicate call can be avoided. 

The `compensate` parameter is also a `ZFlow`, but it's purpose is to do something that "reverts" the performed activity. It is called in case the transaction that invoked the activity has been rolled back, either for be retried, or because of a failure. In the above example if we assume that `sendSMS` does not immediately send the message just schedules it on the Twilio side, we may write a `compensate` flow that calls a different Twilio API to cancel the scheduled message. 

### Running activities in flows
The `Activity` type defines two ways to run the activity:

- the `apply` method gets the activity's parameters as a parameter; using the activity like this is very similar to calling a function
- the `fromInput` method gets the activity's paramters from `ZFlow`s input

### Transforming activities
The activity allows to call `contramap` on it and provide a function that transforms the activity's input before the activity gets called. One very important use case for this is passing configuration values automatically to an activity's operation.

In the `sendSMS` example above we define the operation using the HTTP DSL (described below), and that particular operation has the input type `(String, String, CreateMessage)`. The first string is the "hole" in the path, the second is the auth header, and the third one is the request body. We can hide the authentication from the activity's user by using `contramap` and passing `Remote.config` values in place of the first two element of the operation's input, leaving only the `CreateMessage` body to be sent as a parameter to the activity.

Similarly the activity's output can be transformed with `mapResult` if needed.

## HTTP
The only built-in _operation type_ supported by ZIO Flow at the moment is the `HTTP` operation. A HTTP operation describes a call to a HTTP API, and it can be built using a _DSL_ that is accessible through the `API` object in the `zio.flow.operation.http` package.

Each method on the `API` object adds some information about how the HTTP request should be composed or how its response should be interpreted. Some of these methods append additional input or output types to the operation's type signature. 

For example if we define an operation that requires a `String` as part of the request's path, and an integer to be sent in the body, and expect a list of strings to be parsed from the response body:

```scala mdoc:silent
val api1 = API.get("something" / string).input[Int].output[List[String]]
```

we get a value with the type `API[(String, Int), Unit]`. 

This can be wrapped in an `Operation.Http` to create an `Operation` that can be used in an `Activity`:

```scala mdoc:silent
val op1 = Operation.Http(host = "https://example.com", api1)
```

The `op1` value has the type `Operation.Http[(String, Int), List[String]]`.

The following table lists all the methods available on the `API` object to start defining a HTTP call:

| Method   | Parameters | Description                 |
|----------|------------|-----------------------------|
| `get`    | `Path`     | Defines a `GET` request.    |
| `post`   | `Path`     | Defines a `POST` request.   |
| `put`    | `Path`     | Defines a `PUT` request.    |
| `delete` | `Path`     | Defines a `DELETE` request. |
| `patch`  | `Path`     | Defines a `PATCH` request.  |

The `Path` parameter passed to these represent the _path_ part of the URL of the request.

A `Path` is constructed from multiple segments. Each segment is either a static string or a value received as the operation's input. The segments can be concatenated by either the `/` operator, or the `+` operator. The input-dependent path segments can have various types, and ZIO Flow defines a couple of helper methods to express them in a nice readable way. For example the following path expression defines a path that contains dynamic integer and UUID parts:

```scala mdoc:silent
val path1 = Path.path("api") / "v1" / "users" / int / "artifact" / uuid
```

This has the type `Path[(Int, java.util.UUID)]`.

Note that we had to explicitly wrap the first string segment with `Path.path`. This is usually not necessary when using the path DSL because in that case the compiler already expects a `Path` type and implicitly converts the string to a `Path`.

The result of these is an `API` value that exposes further methods to customize the call:

| Method   | Parameters               | Description                                                 |
|----------|--------------------------|-------------------------------------------------------------|
| `header` | `Header`                 | Adds a header to the request.                               |
| `input`  | `ContentType`, `Schema`  | Defines the type of the request body, to be sent as JSON    |
| `output` | `Schema`                 | Defines the type of the response body, to be parsed as JSON |
| `query`  | `Query`                  | Defines query parameters to be added                        |

The `Header` and `Query` DSLs are similar to the `Path` in that they extend the operation's input types with new elements.

The `Header.string("X-Custom-Header")` defines a custom header to be sent in the request, and it adds a `String` input to the operation. To send more than one headers, the `++` operator can be used to create a sequence of them.

The `Query` builder is very similar, but just like for `Path`s, we have a couple of predefined builders for common types. For both `Header` and `Query` there is a `?` operator defined on these types, converting the given header or query parameter to optional. That changes its type in the _input type_ to an `Option[A]`.

An example for defining query parameters could be:

```scala mdoc:silent
val query1 = long("timestamp") ++ string("description").? ++ uuid("id")
```

which has the type `Query[(Long, Option[String], java.util.UUID)]`.

## Custom
The `Operation` type is not a sealed class, it is one of the extension points of ZIO Flow. For custom operations you also need to install a custom _operation executor_ on the server that executes the ZIO Flow programs. This is described in the [execution section](execution#custom-operation-executor).

## Activity libraries

ZIO Flow defines some predefined set of activities in _activity libraries_. These are separate artifacts published for both the JVM and Scala.JS, `Activity` definitions and corresponding data types with schema. 

We have the following activity libraries at the moment: 

### Twilio

To use the [Twilio](https://www.twilio.com/) activity library, add the following dependency to your project:

```scala
libraryDependencies += "dev.zio" %% "zio-flow-twilio" %  "@VERSION@"
```

This library defines the following activities:

- `sendSMS` - sends an SMS to a phone number of registered target, either immediately or scheduled
- `deleteMessageActivity` - deletes a message from the Twilio account; if it is a scheduled message, this will prevent it from being sent

The library defines a case class called `CreateMessage` that describes the message to be sent, and `Message` represents an SMS registered in the system.

The following example parameter for the `sendSMS` activity defines a scheduled SMS to be sent to a phone number:

```scala mdoc:reset:silent
import zio.flow._
import zio.flow.activities.twilio._
import java.time.Instant

CreateMessage(
  To = PhoneNumber("+15558675310"),
  MessagingServiceSid = Some(MessagingServiceSid("MGXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX")),
  Body = "This is a scheduled message",
  SendAt = Some(Instant.parse("2021-11-30T20:36:27Z")),
  StatusCallback = Some(CallbackUrl("https://webhook.site/xxxxx")),
  ScheduleType = Some(MessageScheduleType.fixed)
)
```

To use these activities, you need to provide your [Twilio account SID and auth token](https://www.twilio.com/docs/iam/api/authtoken) under the following configuration keys:

- `authentication.twilio.account_sid`
- `authentication.twilio.auth_token`

### SendGrid
The [SendGrid](https://sendgrid.com/) activity library is available under the following dependency:

```scala
libraryDependencies += "dev.zio" %% "zio-flow-sendgrid" %  "@VERSION@"
```

It currently defines a single activity called `sendMail`. 
To use this activity, you need to create a [SendGrid API key](https://docs.sendgrid.com/ui/account-and-settings/api-keys) and set it under the following configuration key:

- `authentication.sendgrid.api_key`

The following code snippet shows an example of using the `Mail` data structure to send an email via SendGrid:

```scala mdoc:silent
import zio.flow.activities.sendgrid._

val exampleMail: Mail =
    Mail(
      personalizations = List(
        Personalization(
          to = List(
            EmailAddress(email = "john_doe@example.com", name = "John Doe"),
            EmailAddress(email = "julia_doe@example.com", name = "Julia Doe")
          ),
          cc = Some(
            List(
              EmailAddress(email = "jane_doe@example.com", name = "Jane Doe")
            )
          ),
          bcc = Some(
            List(
              EmailAddress(email = "james_doe@example.com", name = "Jim Doe")
            )
          )
        ),
        Personalization(
          from = Some(
            EmailAddress(
              email = "sales@example.com",
              name = "Example Sales Team"
            )
          ),
          to = List(
            EmailAddress(email = "janice_doe@example.com", name = "Janice Doe")
          ),
          bcc = Some(
            List(
              EmailAddress(email = "jordan_doe@example.com", name = "Jordan Doe")
            )
          )
        )
      ),
      from = EmailAddress(email = "orders@example.com", name = "Example Order Confirmation"),
      reply_to = Some(EmailAddress(email = "customer_service@example.com", name = "Example Customer Service Team")),
      subject = "Your Example Order Confirmation",
      content = List(
        Content(
          `type` = "text/html",
          value =
            "<p>Hello from Twilio SendGrid!</p><p>Sending with the email service trusted by developers and marketers for <strong>time-savings</strong>, <strong>scalability</strong>, and <strong>delivery expertise</strong>.</p><p>%open-track%</p>"
        )
      ),
      attachments = Some(
        List(
          Attachment(
            content =
              "PCFET0NUWVBFIGh0bWw+CjxodG1sIGxhbmc9ImVuIj4KCiAgICA8aGVhZD4KICAgICAgICA8bWV0YSBjaGFyc2V0PSJVVEYtOCI+CiAgICAgICAgPG1ldGEgaHR0cC1lcXVpdj0iWC1VQS1Db21wYXRpYmxlIiBjb250ZW50PSJJRT1lZGdlIj4KICAgICAgICA8bWV0YSBuYW1lPSJ2aWV3cG9ydCIgY29udGVudD0id2lkdGg9ZGV2aWNlLXdpZHRoLCBpbml0aWFsLXNjYWxlPTEuMCI+CiAgICAgICAgPHRpdGxlPkRvY3VtZW50PC90aXRsZT4KICAgIDwvaGVhZD4KCiAgICA8Ym9keT4KCiAgICA8L2JvZHk+Cgo8L2h0bWw+Cg==",
            filename = "index.html",
            `type` = "text/html",
            disposition = "attachment"
          )
        )
      ),
      categories = Some(List(CategoryName("cake"), CategoryName("pie"), CategoryName("baking"))),
      send_at = Some(1617260400),
      batch_id = Some(BatchId("AsdFgHjklQweRTYuIopzXcVBNm0aSDfGHjklmZcVbNMqWert1znmOP2asDFjkl")),
      asm = Some(
        Asm(
          group_id = 12345,
          groups_to_display = List(12345)
        )
      ),
      ip_pool_name = Some("transactional email"),
      mail_settings = Some(
        MailSettings(
          bypass_list_management = Some(Setting(enable = false)),
          footer = Some(Footer(enable = false)),
          sandbox_mode = Some(Setting(enable = false))
        )
      ),
      tracking_settings = Some(
        TrackingSettings(
          click_tracking = Some(
            ClickTracking(
              enable = true,
              enable_text = false
            )
          ),
          open_tracking = Some(
            OpenTracking(
              enable = true,
              substitution_tag = Some("%open-track%")
            )
          ),
          subscription_tracking = Some(SubscriptionTracking(enable = false))
        )
      )
    )
```

### Write your own
It is recommended to write reusable activities for your own use cases, and package them to similar activity libraries. Both the [twilio](https://github.com/zio/zio-flow/tree/main/activities/zio-flow-twilio/) and the [sendgrid](https://github.com/zio/zio-flow/tree/main/activities/zio-flow-sendgrid/) libraries are good examples of how to do this.