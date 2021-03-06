package com.softwaremill.votereporter.votes

import java.text.SimpleDateFormat
import java.util.Locale

import akka.actor.Actor
import com.softwaremill.votereporter.common.LogStart
import com.softwaremill.votereporter.config.VoteReporterConfig
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.json4s.DefaultFormats
import org.json4s.ext.JodaTimeSerializers
import spray.client.pipelining._
import spray.http._
import spray.httpx.Json4sJacksonSupport

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}


case class Retry(voteRequest: VoteRequest, retriesLeft: Int, scheduleDelay: FiniteDuration)

object Retry {

  import scala.concurrent.duration._

  val Retries = 10

  val InitialScheduleDelay = 20.seconds
}

class VoteReporterActor(config: VoteReporterConfig) extends Actor with LazyLogging with LogStart with Json4sJacksonSupport {

  import Retry.{InitialScheduleDelay, Retries}

  override implicit def json4sJacksonFormats = new DefaultFormats {
    override protected def dateFormatter =
      new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
  } ++ JodaTimeSerializers.all

  import context.dispatcher

  override def receive = {
    case voteRequest: VoteRequest =>
      self ! Retry(voteRequest, Retries, InitialScheduleDelay)
    case Retry(voteRequest, retriesLeft, delay) =>
      val pipeline: HttpRequest => Future[HttpResponse] = sendReceive

      val responseFuture: Future[HttpResponse] = pipeline(Post(config.voteCounterEndpoint, voteRequest))

      responseFuture.andThen {
        case Failure(e) =>
          logger.debug(s"Failed to post $voteRequest. Retrying.")
          scheduleDelayed(voteRequest, retriesLeft, delay)
        case Success(response) =>
          logger.info(s"Posted $voteRequest.")
      }
  }

  private def scheduleDelayed(voteRequest: VoteRequest, retriesLeft: Int, scheduleDelay: FiniteDuration) = {
    if (retriesLeft > 0) {
      context.system.scheduler.scheduleOnce(scheduleDelay, self, Retry(voteRequest, retriesLeft - 1, scheduleDelay * 2))
    } else {
      logger.warn(s"Could not post $voteRequest. Retries limit exhausted.")
    }
  }

}
