package cromwell.backend.impl.spark

import java.nio.file.Path

import akka.actor.ActorSystem
import cromwell.backend.impl.spark.SparkClusterProcess.{ParserResponse, TerminalStatus}
import spray.http.{HttpRequest, HttpResponse, StatusCodes}
import spray.json.DefaultJsonProtocol
import spray.client.pipelining._

import scala.concurrent.{ExecutionContext, Future, Promise}
import org.json4s._
import org.json4s.jackson.JsonMethods._
import better.files._
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory
import spray.httpx.unmarshalling._

import scala.util.{Failure, Success, Try}

object SparkClusterProcess {
  sealed trait SparkClusterResponse
  case class SuccessResponse(action: String, driverState: String, serverSparkVersion: String,
                             submissionId: String, success: Boolean, workerHostPort: String, workerId: String) extends SparkClusterResponse
  sealed trait ParserResponse
  case class SuccessfulParsedResponse(id: String, status: Boolean) extends ParserResponse

  sealed trait TerminalStatus
  case class Failed(error: Throwable) extends TerminalStatus
  case object Finished extends TerminalStatus

  object SparkClusterJsonProtocol extends DefaultJsonProtocol {
    implicit val sparkStatusResponseFormat = jsonFormat7(SuccessResponse)
    implicit val formats = DefaultFormats
  }
}

object SparkClusterConstants {
  lazy val SparkClusterMasterDefault = "spark-master"
  val HttpProtocol = "http://"
  val SparkRestApiPort = "6066"
  val SparkClusterIdentifier = Seq("6066", "spark")
  val SparkClusterDeployMode = "cluster"
  val PossibleSparkClusterMaster = Seq("spark", "Spark")
  val SubmitJobJson = "%s.json"
  val ReturnCodeFile = "rc"
  val RunningStatus = "RUNNING"
  val FinishedStatus = "FINISHED"
  val FailedStatus = "FAILED"
  val FAILED = -1
  val FINISHED = 0
  val sleepTime = 1000
  val submissionIdInJson = "submissionId"
  val successInJson = "success"
  val submissionsApiLink = "v1/submissions/status"
  lazy val SparkClusterMasterHostName = Try(sys.env("HOSTNAME")) match {
    case Success(s) => Some(s)
    case Failure(_) => None
  }
  val sparkClusterSubmissionResponseRegex = """(?s)\{(.*?)}""".r.unanchored
  lazy val SparkClusterMasterRestUri = SparkClusterMasterHostName
    .map(p => String.format("%s%s%s", HttpProtocol, p, s":$SparkRestApiPort"))
    .getOrElse(String.format("%s%s%s", HttpProtocol, SparkClusterMasterDefault, s":$SparkRestApiPort"))
}

trait SparkClusterRestClient {
  def sendAndReceive: SendReceive
  def makeHttpRequest(httpRequest: HttpRequest): Future[HttpResponse]
}

trait SparkClusterProcessMonitor {
  def startMonitoringSparkClusterJob(jobPath: Path,  jsonFile: String): Future[TerminalStatus]
  def monitorSparkClusterJob(subId: String, rcPath: Path, promise: Promise[Unit]): Unit
  def evaluateMonitoringFuture(rcPath: Path): Unit
  def completeMonitoringProcess(rcPath: Path, status: String, promise: Promise[Unit]): Unit
}

trait SparkClusterJobParser {
  def parseJsonForSubmissionIdAndStatus(jsonFile: Path): ParserResponse
}

class SparkClusterProcess(implicit system: ActorSystem) extends SparkProcess
  with SparkClusterRestClient with SparkClusterJobParser with SparkClusterProcessMonitor {

  import SparkClusterConstants._
  import SparkClusterProcess._
  import spray.httpx.SprayJsonSupport._
  import SparkClusterJsonProtocol._

  implicit lazy val ec: ExecutionContext = system.dispatcher
  lazy val completionPromise = Promise[TerminalStatus]()
  lazy val monitorPromise = Promise[Unit]()
  val tag = this.getClass.getSimpleName
  lazy val logger = Logger(LoggerFactory.getLogger(getClass.getName))

  override def sendAndReceive: SendReceive = sendReceive

  override def startMonitoringSparkClusterJob(jobPath: Path,  jsonFile: String): Future[TerminalStatus] = {
    Future(parseJsonForSubmissionIdAndStatus(jobPath.resolve(jsonFile))) onComplete  {
      case Success(resp: SuccessfulParsedResponse) =>
        val rcPath = jobPath.resolve(ReturnCodeFile)
        monitorSparkClusterJob(resp.id, rcPath, monitorPromise)
        evaluateMonitoringFuture(rcPath)
      case Failure(exception: Throwable) =>
        logger.error(s"{} Spark Job failed to submit successfully following reason: {}", tag, exception.getMessage)
        completionPromise success Failed(exception)
    }
    completionPromise.future
  }

  override def monitorSparkClusterJob(subId: String,  rcPath: Path, promise: Promise[Unit]): Unit = {
    pollForJobStatus(subId) onComplete {
      case Success(resp: SuccessResponse) if resp.driverState == RunningStatus => Thread.sleep(sleepTime)
        logger.debug(s"{} Spark Driver is now in :{} state", tag, RunningStatus)
        monitorSparkClusterJob(subId, rcPath, promise)
      case Success(resp: SuccessResponse) if resp.driverState == FinishedStatus =>
        logger.debug(s"{} Spark Driver is now in :{} state", tag, FinishedStatus)
        completeMonitoringProcess(rcPath, FINISHED.toString, promise)
      case Success(resp: SuccessResponse) if resp.driverState == FailedStatus =>
        logger.debug(s"{} Spark Driver is now in :{} state", tag, FailedStatus)
        completeMonitoringProcess(rcPath, FAILED.toString, promise)
      case Failure(error) =>
        logger.error(s"{} Spark cluster poll for status failed Reason: {} and error: {}", tag, error.getMessage, error)
        completeMonitoringProcess(rcPath, FAILED.toString, promise)
    }
  }

  override def evaluateMonitoringFuture(rcPath: Path) = {
    monitorPromise.future.onComplete {
      case Success(_) =>
        rcPath.contentAsString.stripLineEnd.toInt match {
          case FINISHED => completionPromise success Finished
          case FAILED => completionPromise success Failed(new IllegalStateException("Spark Driver returned failed status"))
        }
      case Failure(err) => completionPromise success Failed(err)
    }
  }

  override def completeMonitoringProcess(rcPath: Path, status: String, promise: Promise[Unit]) = {
    rcPath < status
    promise success()
  }

  def pollForJobStatus(subId: String): Future[SparkClusterResponse] = {
    val request = Get(s"$SparkClusterMasterRestUri/$submissionsApiLink/$subId")
    makeHttpRequest(request) flatMap { v =>
      v.status match {
        case StatusCodes.OK => Future(v ~> unmarshal[SuccessResponse])
        case _ =>
          val msg = s"Unexpected response received in response from Spark rest api. Response: $v"
          logger.error("{} reason: {}", tag, msg)
          throw new IllegalStateException(msg)
      }
    } recover {
      case error => throw new IllegalStateException(s"Reason: ${error.getMessage}", error)
    }
  }

  override def parseJsonForSubmissionIdAndStatus(jsonFile: Path): ParserResponse = {
    val lines = jsonFile.contentAsString
    val line = sparkClusterSubmissionResponseRegex findFirstIn lines match {
      case Some(content) => content
      case None =>
        val msg = "Unable to get json out of submission response file"
        logger.error("{} reason: {}", tag, msg)
        throw new IllegalStateException(msg)
    }
    val json = parse(line)
    SuccessfulParsedResponse((json \ submissionIdInJson).extract[String], (json \ successInJson).extract[Boolean])
  }

  override def makeHttpRequest(httpRequest: HttpRequest): Future[HttpResponse] = {
    val headers = httpRequest.headers
    sendAndReceive(httpRequest.withHeaders(headers))
  }
}
