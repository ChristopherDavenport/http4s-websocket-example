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

  trait Messages


  def wsResponse[F[_]](countTo: Int)(implicit F: Effect[F], ec: ExecutionContext, S: Scheduler): F[Response[F]] = {
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
          S.awakeEvery(1.seconds).evalMap{_ => counter.modify(_ + 1).flatMap{
            case async.Ref.Change(prev, now) => 
              messageQueue.enqueue1(Text(s"Currently $now"))
                .flatMap{_ => 
                  if (now >= countTo){
                    finalMessage.get.flatMap{t =>  messageQueue.enqueue1(Text(t.getOrElse("No Message Received")))} *> completed.set(true)
                  } else {
                    F.delay(())
                  }
                }
          }}
      )
    }

    def fromClient(ref: async.Ref[F, Option[String]]): Sink[F, WebSocketFrame] = _.evalMap { (ws: WebSocketFrame) =>
      ws match {
        case Text(t, _) => F.delay(println(t)) *> ref.setSync(t.some)
        case f => F.delay(println(s"Unknown type: $f"))
      }
    }

    for {
      completed <- async.signalOf[F, Boolean](false)
      ref <- async.refOf[F, Int](0)
      finalMessage <- async.refOf[F, Option[String]](Option.empty)
      messageQueue <- async.boundedQueue[F, WebSocketFrame](100)
      clientOut = toClient(ref, finalMessage, completed, messageQueue)
      clientIn = fromClient(finalMessage)

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
          resp <- wsResponse(int)
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