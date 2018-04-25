package io.chrisdavenport.http4swebsocketexample

import fs2._
import cats.effect._
import cats._
import cats.implicits._
import scala.concurrent.ExecutionContext
import org.http4s._
import org.http4s.dsl._
import org.http4s.server.blaze._
import org.http4s.server.websocket._
import org.http4s.websocket.WebsocketBits._
import scala.concurrent.duration._

object Server {

  /**
    * Generate Response Based on an Integer requested
    * to count up to
    * 
    * @param countTo: Number of Seconds To Count Up To.
    * @param F: The Effect Context To Operate Asynchronously.
    * @param ec: ExecutionContext to operate asynchronously on.
    * @param S: Scheduler that allows timing to be evaluated.
    *
    */
  def counterResponse[F[_]](countTo: Int)(implicit F: Effect[F], ec: ExecutionContext, S: Scheduler): F[Response[F]] = {
    
    /**
      * Generates the stream of websocket frames that are sent to the connected
      * client.
      *
      * Messages are dequeued from the provided queue which is interrupted when
      * the provide completed signal is flipped to true. 
      * Asynchronously, awake every second increment the counter, then emit
      * a message showing the count we have reached.
      * If we have reached the final value of the counter enqueue the final message
      * or a default message, send a close frame, and then terminate the stream.
      * If it is not the final message at that point do nothing.
      *
      * @param counter: This is a counter that is incremented depending on how long
      * the system has been operating with this request
      * @param finalMessage: An Option of a String that will be analyzed and output
      * when the count is complete
      * @param completed: A Signal which when true will stop any more frames from
      * being sent to the client
      * @param messageQueue: This is the queue we are pulling from to generate
      * the messages that are going out the client.
      */
    def toClient(
      counter: async.Ref[F, Int],
      finalMessage: async.Ref[F, Option[String]],
      completed: async.mutable.Signal[F, Boolean], 
      messageQueue: async.mutable.Queue[F, WebSocketFrame]
      ): Stream[F, WebSocketFrame] =  {
      messageQueue
      .dequeue
      .interruptWhen(completed)
      .concurrently(
          S.awakeEvery(1.seconds)
          .evalMap{_ => 
            counter.modify(_ + 1)
            .flatMap{ 
              case async.Ref.Change(_, now) => 
                messageQueue.enqueue1(Text(s"Currently $now"))
                .flatMap{_ => 
                  if (now >= countTo){
                    finalMessage.get.flatMap{t =>  messageQueue.enqueue1(Text(t.getOrElse("No Message Received")))} *>
                    messageQueue.enqueue1(Close()) *>
                    completed.set(true)
                  } else {
                    F.delay(())
                  }
                }
          }}
      )
    }

    /**
      * From Client Represents What We Do With Messages Received from the Client
      *
      * This generally serves as a println factory for incomming frames
      * associating when they are received. If it is a text frame the 
      * final message returned is updated.
      *
      * @param counter: This is a counter that is being operated elsewhere to 
      * inform us what is the current count when any message is received.
      * @param ref:  This is the final message that is updated when we
      * receive a Text frame we set that to the current final message value.
      */
    def fromClient(
      counter: async.Ref[F, Int],
      ref: async.Ref[F, Option[String]]
    ): Sink[F, WebSocketFrame] = _.evalMap { (ws: WebSocketFrame) =>
      ws match {
        case Text(t, _) => 
          counter.get.flatMap{ count => 
            F.delay(println("Count: $count " + t)) *> ref.setSync(t.some)
          }
        case f => 
          counter.get.flatMap{ count => 
            F.delay(println(s"Count: $count Unknown type: $f"))
          }
      }
    }

    // Here We Construct the Tools Required for 
    // the toClient and fromClient Functions
    // finally we Construct the websocket response from these.
    for {
      completed <- async.signalOf[F, Boolean](false)
      ref <- async.refOf[F, Int](0)
      finalMessage <- async.refOf[F, Option[String]](Option.empty)
      messageQueue <- async.boundedQueue[F, WebSocketFrame](100)
      clientOut = toClient(ref, finalMessage, completed, messageQueue)
      clientIn = fromClient(ref, finalMessage)

      resp <- WebSocketBuilder[F].build(clientOut, clientIn)
    } yield resp
  }

  def service[F[_]](implicit F: Effect[F], ec: ExecutionContext, S: Scheduler): HttpService[F] = {
    val dsl = new Http4sDsl[F]{}
    import dsl._

    HttpService[F]{
      case GET -> Root / "counter" / countTo => 
        for {
          int <- Sync[F].delay(countTo.toInt)
          resp <- counterResponse(int)
        } yield resp
    }
  }

  def server[F[_]: Effect](implicit ec: ExecutionContext): Stream[F, StreamApp.ExitCode] = for {
    s <- Scheduler(10)
    exitCode <- BlazeBuilder[F]
      .bindHttp(8080, "0.0.0.0")
      .mountService(service[F](Effect[F], ec, s), "/")
      .serve
  } yield exitCode
}