package cromwell.backend.impl.spark

import java.io.{File, FileWriter, Writer}
import java.nio.file.{Path, Paths}

import akka.testkit.{ImplicitSender, TestActorRef}
import com.typesafe.config.ConfigFactory
import cromwell.backend.{BackendConfigurationDescriptor, BackendJobDescriptor, BackendSpec}
import cromwell.backend.BackendJobExecutionActor.{FailedNonRetryableResponse, SucceededResponse}
import better.files._
import spray.httpx.unmarshalling._

import scala.concurrent.Promise
import cromwell.backend.io._
import cromwell.core.{PathWriter, TailedWriter, TestKitSuite, UntailedWriter}
import org.mockito.Matchers._
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfter, Matchers, WordSpecLike}
import spray.http._
import spray.json._
import wdl4s._
import wdl4s.values.WdlValue

import scala.concurrent.duration._
import scala.sys.process.{Process, ProcessLogger}
import spray.httpx.SprayJsonSupport._
import cromwell.backend.impl.spark.SparkClusterProcess._
import SparkClusterJsonProtocol._
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

class SparkJobExecutionActorSpec extends TestKitSuite("SparkJobExecutionActor")
  with WordSpecLike
  with Matchers
  with MockitoSugar
  with BeforeAndAfter
  with ImplicitSender {

  import BackendSpec._


  private val sparkProcess: SparkProcess = mock[SparkProcess]
  private val sparkCommands: SparkCommands = new SparkCommands
  private val sparkClusterProcess: SparkClusterProcess = mock[SparkClusterProcess]

  private val helloWorldWdl =
    """
      |task hello {
      |
      |  command {
      |    sparkApp
      |  }
      |  output {
      |    String salutation = read_string(stdout())
      |  }
      |  RUNTIME
      |}
      |
      |workflow hello {
      |  call hello
      |}
    """.stripMargin

  private val helloWorldClusterWdl =
    """
      |task helloClusterMode {
      |
      | command {
      |   sparkApp
      |  }
      | output {
      |   String salutation = read_string(stdout())
      | }
      | RUNTIME
      |}
      |
      |workflow helloClusterMode {
      |   call helloClusterMode
      |}
    """.stripMargin

  private val mockSuccessClusterResponse = SuccessResponse(action = "SubmissionStatusResponse", driverState = "FINISHED", serverSparkVersion = "1.6.1",
    submissionId = "driver-20160803181054-0000", success = true, workerHostPort = "10.0.1.55:43834", workerId = "worker-20160801162431-10.0.1.55-43834")

  private val mockFailedClusterResponse = SuccessResponse(action = "SubmissionStatusResponse", driverState = "FAILED", serverSparkVersion = "1.6.1",
    submissionId = "driver-20160803181054-0000", success = true, workerHostPort = "10.0.1.55:43834", workerId = "worker-20160801162431-10.0.1.55-43834")

  private val mockSuccessHttpResponse = HttpResponse(StatusCodes.OK, HttpEntity(ContentTypes.`application/json`, mockSuccessClusterResponse.toJson.toString))

  private val mockFailedHttpResponse = HttpResponse(StatusCodes.OK, HttpEntity(ContentTypes.`application/json`, mockFailedClusterResponse.toJson.toString))

  private val mockBadHttpResponse = HttpResponse(StatusCodes.BadRequest)

  private val sampleSubmissionResponse =
    """
      |Running Spark using the REST application submission protocol.
      |16/08/06 18:35:26 INFO rest.RestSubmissionClient: Submitting a request to launch an application in spark://host-10-0-1-53:6066.
      |16/08/06 18:35:27 INFO rest.RestSubmissionClient: Submission successfully created as driver-20160806183527-0006. Polling submission state...
      |16/08/06 18:35:27 INFO rest.RestSubmissionClient: Submitting a request for the status of submission driver-20160806183527-0006 in spark://host-10-0-1-53:6066.
      |16/08/06 18:35:27 INFO rest.RestSubmissionClient: State of driver driver-20160806183527-0006 is now RUNNING.
      |16/08/06 18:35:27 INFO rest.RestSubmissionClient: Driver is running on worker worker-20160801162431-10.0.1.55-43834 at 10.0.1.55:43834.
      |16/08/06 18:35:27 INFO rest.RestSubmissionClient: Server responded with CreateSubmissionResponse:
      |{
      |  "action" : "CreateSubmissionResponse",
      |  "message" : "Driver successfully submitted as driver-20160806183527-0006",
      |  "serverSparkVersion" : "1.6.1",
      |  "submissionId" : "driver-20160806183527-0006",
      |  "success" : true
      |}
    """.stripMargin

  private val failedOnStderr =
    """
      |runtime {
      | appMainClass: "test"
      | failOnStderr: true
      |}
    """.stripMargin

  private val passOnStderr =
    """
      |runtime {
      | appMainClass: "test"
      | failOnStderr: false
      |}
    """.stripMargin

  private val backendClusterConfig = ConfigFactory.parseString(
    s"""{
        |  root = "local-cromwell-executions"
        |  filesystems {
        |    local {
        |      localization = [
        |        "hard-link", "soft-link", "copy"
        |      ]
        |    }
        |  }
        |  master: "spark"
        |  deployMode: "cluster"
        |}
        """.stripMargin)

  private val backendClientConfig = ConfigFactory.parseString(
    s"""{
        |  root = "local-cromwell-executions"
        |  filesystems {
        |    local {
        |      localization = [
        |        "hard-link", "soft-link", "copy"
        |      ]
        |    }
        |  }
        |  master: "local"
        |  deployMode: "client"
        |}
        """.stripMargin)

  private val timeout = Timeout(20.seconds)

  after {
    Mockito.reset(sparkProcess)
    Mockito.reset(sparkClusterProcess)
  }

  before {
    when(sparkClusterProcess.logger).thenReturn(Logger(LoggerFactory.getLogger(getClass.getName)))
    when(sparkClusterProcess.completeMonitoringProcess(any[Path], any[String], any[Promise[Unit]])).thenCallRealMethod()
    when(sparkClusterProcess.evaluateMonitoringFuture(any[Path])).thenCallRealMethod()
    when(sparkClusterProcess.completionPromise).thenReturn(Promise[TerminalStatus]())
    when(sparkClusterProcess.monitorPromise).thenReturn(Promise[Unit]())
    when(sparkClusterProcess.ec).thenReturn(system.dispatcher)
    when(sparkClusterProcess.startMonitoringSparkClusterJob(any[Path], any[String])).thenCallRealMethod()
    when(sparkClusterProcess.monitorSparkClusterJob(any[String], any[Path], any[Promise[Unit]])).thenCallRealMethod()
    when(sparkClusterProcess.pollForJobStatus(any[String])).thenCallRealMethod()
    when(sparkClusterProcess.parseJsonForSubmissionIdAndStatus(any[Path])).thenCallRealMethod()
  }

  override def afterAll(): Unit = system.shutdown()

  "executeCluster method" should {
    "return finished status when keyword \"spark\" is used as master attribute along with \"cluster\" for deployMode" in {
      val jobDescriptor = prepareJob(helloWorldClusterWdl, passOnStderr, isCluster = true)
      val (job, jobPaths, backendConfigDesc) = (jobDescriptor.jobDescriptor, jobDescriptor.jobPaths, jobDescriptor.backendConfigurationDescriptor)
      val stubProcess = mock[Process]
      val stubUntailed = new UntailedWriter(jobPaths.stdout) with MockPathWriter
      val stubTailed = new TailedWriter(jobPaths.stderr, 100) with MockPathWriter
      val subId = "driver-20160803181054-0000"
      val status = true
      val stderr = ""

      jobPaths.callRoot.resolve("cluster.json") < sampleSubmissionResponse

      val backend = TestActorRef(new SparkJobExecutionActor(job, backendConfigDesc) {
        override lazy val clusterExtProcess = sparkClusterProcess
        override lazy val cmds = sparkCommands
      }).underlyingActor

      when(sparkClusterProcess.commandList(any[String])).thenReturn(Seq.empty[String])
      when(sparkClusterProcess.externalProcess(any[Seq[String]], any[ProcessLogger])).thenReturn(stubProcess)
      when(stubProcess.exitValue()).thenReturn(0)
      when(sparkClusterProcess.tailedWriter(any[Int], any[Path])).thenReturn(stubTailed)
      when(sparkClusterProcess.untailedWriter(any[Path])).thenReturn(stubUntailed)
      when(sparkClusterProcess.processStderr).thenReturn(stderr)
      when(sparkClusterProcess.makeHttpRequest(any[HttpRequest])).thenReturn(Promise.successful(mockSuccessHttpResponse).future)

      whenReady(backend.execute, timeout) { response =>
        response shouldBe a[SucceededResponse]
        verify(sparkClusterProcess, times(1)).externalProcess(any[Seq[String]], any[ProcessLogger])
        verify(sparkClusterProcess, times(1)).tailedWriter(any[Int], any[Path])
        verify(sparkClusterProcess, times(1)).untailedWriter(any[Path])
        verify(sparkClusterProcess, times(1)).parseJsonForSubmissionIdAndStatus(any[Path])
        verify(sparkClusterProcess, times(1)).makeHttpRequest(any[HttpRequest])
        verify(sparkClusterProcess, times(1)).pollForJobStatus(any[String])
      }
      cleanUpJob(jobPaths)
    }

    "return finished status when appropriate spark rest api is used as master attribute along with \"Cluster\" for deployMode" in {
      val jobDescriptor = prepareJob(helloWorldClusterWdl, passOnStderr, isCluster = true)
      val (job, jobPaths, backendConfigDesc) = (jobDescriptor.jobDescriptor, jobDescriptor.jobPaths, jobDescriptor.backendConfigurationDescriptor)
      val stubProcess = mock[Process]
      val stubUntailed = new UntailedWriter(jobPaths.stdout) with MockPathWriter
      val stubTailed = new TailedWriter(jobPaths.stderr, 100) with MockPathWriter
      val subId = "driver-20160803181054-0000"
      val status = true
      val stderr = ""

      jobPaths.callRoot.resolve("cluster.json") < sampleSubmissionResponse

      val backend = TestActorRef(new SparkJobExecutionActor(job, backendConfigDesc) {
        override lazy val clusterExtProcess = sparkClusterProcess
        override lazy val cmds = sparkCommands
      }).underlyingActor

      when(sparkClusterProcess.commandList(any[String])).thenReturn(Seq.empty[String])
      when(sparkClusterProcess.externalProcess(any[Seq[String]], any[ProcessLogger])).thenReturn(stubProcess)
      when(stubProcess.exitValue()).thenReturn(0)
      when(sparkClusterProcess.tailedWriter(any[Int], any[Path])).thenReturn(stubTailed)
      when(sparkClusterProcess.untailedWriter(any[Path])).thenReturn(stubUntailed)
      when(sparkClusterProcess.processStderr).thenReturn(stderr)
      when(sparkClusterProcess.makeHttpRequest(any[HttpRequest])).thenReturn(Promise.successful(mockSuccessHttpResponse).future)

      whenReady(backend.execute, timeout) { response =>
        response shouldBe a [SucceededResponse]
        verify(sparkClusterProcess, times(1)).externalProcess(any[Seq[String]], any[ProcessLogger])
        verify(sparkClusterProcess, times(1)).tailedWriter(any[Int], any[Path])
        verify(sparkClusterProcess, times(1)).untailedWriter(any[Path])
        verify(sparkClusterProcess, times(1)).parseJsonForSubmissionIdAndStatus(any[Path])
        verify(sparkClusterProcess, times(1)).makeHttpRequest(any[HttpRequest])
        verify(sparkClusterProcess, times(1)).pollForJobStatus(any[String])
      }
      cleanUpJob(jobPaths)
    }

    "return Failed status when there's a zero exit code but stderr is  not empty" in {
      val jobDescriptor = prepareJob(helloWorldClusterWdl, failedOnStderr, isCluster = true)
      val (job, jobPaths, backendConfigDesc) = (jobDescriptor.jobDescriptor, jobDescriptor.jobPaths, jobDescriptor.backendConfigurationDescriptor)
      val stubProcess = mock[Process]
      val stubUntailed = new UntailedWriter(jobPaths.stdout) with MockPathWriter
      val stubTailed = new TailedWriter(jobPaths.stderr, 100) with MockPathWriter

      jobPaths.callRoot.resolve("cluster.json") < sampleSubmissionResponse

      val backend = TestActorRef(new SparkJobExecutionActor(job, backendConfigDesc) {
        override lazy val clusterExtProcess = sparkClusterProcess
        override lazy val cmds = sparkCommands
      }).underlyingActor

      when(sparkClusterProcess.commandList(any[String])).thenReturn(Seq.empty[String])
      when(sparkClusterProcess.externalProcess(any[Seq[String]], any[ProcessLogger])).thenReturn(stubProcess)
      when(stubProcess.exitValue()).thenReturn(0)
      when(sparkClusterProcess.tailedWriter(any[Int], any[Path])).thenReturn(stubTailed)
      when(sparkClusterProcess.untailedWriter(any[Path])).thenReturn(stubUntailed)
      when(sparkClusterProcess.processStderr).thenReturn(sampleSubmissionResponse)

      whenReady(backend.execute, timeout) { response =>
        response shouldBe a [FailedNonRetryableResponse]
        assert(response.asInstanceOf[FailedNonRetryableResponse].throwable.getMessage.contains(s"Execution process failed although return code is zero but stderr is not empty"))
      }
      cleanUpJob(jobPaths)
    }

    "return Failed status when non zero exit code is received for submitting job" in {
      val jobDescriptor = prepareJob(helloWorldClusterWdl, passOnStderr, isCluster = true)
      val (job, jobPaths, backendConfigDesc) = (jobDescriptor.jobDescriptor, jobDescriptor.jobPaths, jobDescriptor.backendConfigurationDescriptor)
      val stubProcess = mock[Process]
      val stubUntailed = new UntailedWriter(jobPaths.stdout) with MockPathWriter
      val stubTailed = new TailedWriter(jobPaths.stderr, 100) with MockPathWriter
      val stderrResult = ""
      val backend = TestActorRef(new SparkJobExecutionActor(job, backendConfigDesc) {
        override lazy val clusterExtProcess = sparkClusterProcess
        override lazy val cmds = sparkCommands
      }).underlyingActor

      when(sparkClusterProcess.commandList(any[String])).thenReturn(Seq.empty[String])
      when(sparkClusterProcess.externalProcess(any[Seq[String]], any[ProcessLogger])).thenReturn(stubProcess)
      when(stubProcess.exitValue()).thenReturn(-1)
      when(sparkClusterProcess.tailedWriter(any[Int], any[Path])).thenReturn(stubTailed)
      when(sparkClusterProcess.untailedWriter(any[Path])).thenReturn(stubUntailed)
      when(sparkClusterProcess.processStderr).thenReturn(stderrResult)

      whenReady(backend.execute, timeout) { response =>
        response shouldBe a [FailedNonRetryableResponse]
        assert(response.asInstanceOf[FailedNonRetryableResponse].throwable.getMessage.contains(s"Execution process failed. Spark returned non zero status code:"))
      }
      cleanUpJob(jobPaths)
    }

    "return failed status if a failed driver state is received after http request" in {
      val jobDescriptor = prepareJob(helloWorldClusterWdl, passOnStderr, isCluster = true)
      val (job, jobPaths, backendConfigDesc) = (jobDescriptor.jobDescriptor, jobDescriptor.jobPaths, jobDescriptor.backendConfigurationDescriptor)
      val stubProcess = mock[Process]
      val stubUntailed = new UntailedWriter(jobPaths.stdout) with MockPathWriter
      val stubTailed = new TailedWriter(jobPaths.stderr, 100) with MockPathWriter
      val subId = "driver-20160803181054-0000"
      val status = true
      val stderr = ""

      jobPaths.callRoot.resolve("cluster.json") < sampleSubmissionResponse

      val backend = TestActorRef(new SparkJobExecutionActor(job, backendConfigDesc) {
        override lazy val clusterExtProcess = sparkClusterProcess
        override lazy val cmds = sparkCommands
      }).underlyingActor

      when(sparkClusterProcess.commandList(any[String])).thenReturn(Seq.empty[String])
      when(sparkClusterProcess.externalProcess(any[Seq[String]], any[ProcessLogger])).thenReturn(stubProcess)
      when(stubProcess.exitValue()).thenReturn(0)
      when(sparkClusterProcess.tailedWriter(any[Int], any[Path])).thenReturn(stubTailed)
      when(sparkClusterProcess.untailedWriter(any[Path])).thenReturn(stubUntailed)
      when(sparkClusterProcess.processStderr).thenReturn(stderr)
      when(sparkClusterProcess.makeHttpRequest(any[HttpRequest])).thenReturn(Promise.successful(mockFailedHttpResponse).future)

      whenReady(backend.execute, timeout) { response =>
        response shouldBe a [FailedNonRetryableResponse]
        assert(response.asInstanceOf[FailedNonRetryableResponse].throwable.getMessage.contains(s"Spark Driver returned failed status"))
      }
      cleanUpJob(jobPaths)
    }

    "return failed status when http client request receives bad request response" in {
      val jobDescriptor = prepareJob(helloWorldClusterWdl, passOnStderr, isCluster = true)
      val (job, jobPaths, backendConfigDesc) = (jobDescriptor.jobDescriptor, jobDescriptor.jobPaths, jobDescriptor.backendConfigurationDescriptor)
      val stubProcess = mock[Process]
      val stubUntailed = new UntailedWriter(jobPaths.stdout) with MockPathWriter
      val stubTailed = new TailedWriter(jobPaths.stderr, 100) with MockPathWriter
      val subId = "driver-20160803181054-0000"
      val status = true
      val stderr = ""

      jobPaths.callRoot.resolve("cluster.json") < sampleSubmissionResponse

      val backend = TestActorRef(new SparkJobExecutionActor(job, backendConfigDesc) {
        override lazy val clusterExtProcess = sparkClusterProcess
        override lazy val cmds = sparkCommands
      }).underlyingActor

      when(sparkClusterProcess.commandList(any[String])).thenReturn(Seq.empty[String])
      when(sparkClusterProcess.externalProcess(any[Seq[String]], any[ProcessLogger])).thenReturn(stubProcess)
      when(stubProcess.exitValue()).thenReturn(0)
      when(sparkClusterProcess.tailedWriter(any[Int], any[Path])).thenReturn(stubTailed)
      when(sparkClusterProcess.untailedWriter(any[Path])).thenReturn(stubUntailed)
      when(sparkClusterProcess.processStderr).thenReturn(stderr)
      when(sparkClusterProcess.makeHttpRequest(any[HttpRequest])).thenReturn(Promise.successful(mockBadHttpResponse).future)

      whenReady(backend.execute, timeout) { response =>
        response shouldBe a [FailedNonRetryableResponse]
        assert(response.asInstanceOf[FailedNonRetryableResponse].throwable.getMessage.contains(s"Spark Driver returned failed status"))
      }
      cleanUpJob(jobPaths)
    }

    "return failed status when json file is empty" in {
      val jobDescriptor = prepareJob(helloWorldClusterWdl, passOnStderr, isCluster = true)
      val (job, jobPaths, backendConfigDesc) = (jobDescriptor.jobDescriptor, jobDescriptor.jobPaths, jobDescriptor.backendConfigurationDescriptor)
      val stubProcess = mock[Process]
      val stubUntailed = new UntailedWriter(jobPaths.stdout) with MockPathWriter
      val stubTailed = new TailedWriter(jobPaths.stderr, 100) with MockPathWriter
      val stderr = ""
      val backend = TestActorRef(new SparkJobExecutionActor(job, backendConfigDesc) {
        override lazy val clusterExtProcess = sparkClusterProcess
        override lazy val cmds = sparkCommands
      }).underlyingActor

      jobPaths.callRoot.resolve("cluster.json") < ""


      when(sparkClusterProcess.commandList(any[String])).thenReturn(Seq.empty[String])
      when(sparkClusterProcess.externalProcess(any[Seq[String]], any[ProcessLogger])).thenReturn(stubProcess)
      when(stubProcess.exitValue()).thenReturn(0)
      when(sparkClusterProcess.tailedWriter(any[Int], any[Path])).thenReturn(stubTailed)
      when(sparkClusterProcess.untailedWriter(any[Path])).thenReturn(stubUntailed)
      when(sparkClusterProcess.processStderr).thenReturn(stderr)
      when(sparkClusterProcess.makeHttpRequest(any[HttpRequest])).thenReturn(Promise.successful(mockBadHttpResponse).future)

      whenReady(backend.execute, timeout) { response =>
        response shouldBe a[FailedNonRetryableResponse]
        assert(response.asInstanceOf[FailedNonRetryableResponse].throwable.getMessage.contains(s"Unable to get json out of submission response file"))
      }
      cleanUpJob(jobPaths)
    }

  }

  "executeTask method" should {
    "return succeeded task status with stdout" in {
      val jobDescriptor = prepareJob()
      val (job, jobPaths, backendConfigDesc) = (jobDescriptor.jobDescriptor, jobDescriptor.jobPaths, jobDescriptor.backendConfigurationDescriptor)
      val stubProcess = mock[Process]
      val stubUntailed = new UntailedWriter(jobPaths.stdout) with MockPathWriter
      val stubTailed = new TailedWriter(jobPaths.stderr, 100) with MockPathWriter
      val stderrResult = ""

      when(sparkProcess.commandList(any[String])).thenReturn(Seq.empty[String])
      when(sparkProcess.externalProcess(any[Seq[String]], any[ProcessLogger])).thenReturn(stubProcess)
      when(stubProcess.exitValue()).thenReturn(0)
      when(sparkProcess.tailedWriter(any[Int], any[Path])).thenReturn(stubTailed)
      when(sparkProcess.untailedWriter(any[Path])).thenReturn(stubUntailed)
      when(sparkProcess.processStderr).thenReturn(stderrResult)

      val backend = TestActorRef(new SparkJobExecutionActor(job, backendConfigDesc) {
        override lazy val extProcess = sparkProcess
        override lazy val cmds = sparkCommands
      }).underlyingActor

      whenReady(backend.execute, timeout) { response =>
        response shouldBe a[SucceededResponse]
        verify(sparkProcess, times(1)).externalProcess(any[Seq[String]], any[ProcessLogger])
        verify(sparkProcess, times(1)).tailedWriter(any[Int], any[Path])
        verify(sparkProcess, times(1)).untailedWriter(any[Path])
      }

      cleanUpJob(jobPaths)
    }

    "return failed task status on non-zero process exit" in {
      val jobDescriptor = prepareJob()
      val (job, jobPaths, backendConfigDesc) = (jobDescriptor.jobDescriptor, jobDescriptor.jobPaths, jobDescriptor.backendConfigurationDescriptor)

      val backend = TestActorRef(new SparkJobExecutionActor(job, backendConfigDesc) {
        override lazy val cmds = sparkCommands
        override lazy val extProcess = sparkProcess
      }).underlyingActor
      val stubProcess = mock[Process]
      val stubUntailed = new UntailedWriter(jobPaths.stdout) with MockPathWriter
      val stubTailed = new TailedWriter(jobPaths.stderr, 100) with MockPathWriter
      val stderrResult = ""

      when(sparkProcess.externalProcess(any[Seq[String]], any[ProcessLogger])).thenReturn(stubProcess)
      when(stubProcess.exitValue()).thenReturn(-1)
      when(sparkProcess.tailedWriter(any[Int], any[Path])).thenReturn(stubTailed)
      when(sparkProcess.untailedWriter(any[Path])).thenReturn(stubUntailed)
      when(sparkProcess.processStderr).thenReturn(stderrResult)

      whenReady(backend.execute, timeout) { response =>
        response shouldBe a[FailedNonRetryableResponse]
        assert(response.asInstanceOf[FailedNonRetryableResponse].throwable.getMessage.contains(s"Execution process failed. Spark returned non zero status code:"))
      }

      cleanUpJob(jobPaths)
    }

    "return failed task status when stderr is non-empty but process exit with zero return code" in {
      val jobDescriptor = prepareJob(runtimeString = failedOnStderr)
      val (job, jobPaths, backendConfigDesc) = (jobDescriptor.jobDescriptor, jobDescriptor.jobPaths, jobDescriptor.backendConfigurationDescriptor)

      val backend = TestActorRef(new SparkJobExecutionActor(job, backendConfigDesc) {
        override lazy val cmds = sparkCommands
        override lazy val extProcess = sparkProcess
      }).underlyingActor
      val stubProcess = mock[Process]
      val stubUntailed = new UntailedWriter(jobPaths.stdout) with MockPathWriter
      val stubTailed = new TailedWriter(jobPaths.stderr, 100) with MockPathWriter
      jobPaths.stderr < "failed"

      when(sparkProcess.externalProcess(any[Seq[String]], any[ProcessLogger])).thenReturn(stubProcess)
      when(stubProcess.exitValue()).thenReturn(0)
      when(sparkProcess.tailedWriter(any[Int], any[Path])).thenReturn(stubTailed)
      when(sparkProcess.untailedWriter(any[Path])).thenReturn(stubUntailed)

      whenReady(backend.execute, timeout) { response =>
        response shouldBe a[FailedNonRetryableResponse]
        assert(response.asInstanceOf[FailedNonRetryableResponse].throwable.getMessage.contains(s"Execution process failed although return code is zero but stderr is not empty"))
      }

      cleanUpJob(jobPaths)
    }

    "return succeeded task status when stderr is non-empty but process exit with zero return code" in {
      val jobDescriptor = prepareJob()
      val (job, jobPaths, backendConfigDesc) = (jobDescriptor.jobDescriptor, jobDescriptor.jobPaths, jobDescriptor.backendConfigurationDescriptor)

      val backend = TestActorRef(new SparkJobExecutionActor(job, backendConfigDesc) {
        override lazy val cmds = sparkCommands
        override lazy val extProcess = sparkProcess
      }).underlyingActor
      val stubProcess = mock[Process]
      val stubUntailed = new UntailedWriter(jobPaths.stdout) with MockPathWriter
      val stubTailed = new TailedWriter(jobPaths.stderr, 100) with MockPathWriter

      when(sparkProcess.externalProcess(any[Seq[String]], any[ProcessLogger])).thenReturn(stubProcess)
      when(stubProcess.exitValue()).thenReturn(0)
      when(sparkProcess.tailedWriter(any[Int], any[Path])).thenReturn(stubTailed)
      when(sparkProcess.untailedWriter(any[Path])).thenReturn(stubUntailed)

      whenReady(backend.execute, timeout) { response =>
        response shouldBe a[SucceededResponse]
        verify(sparkProcess, times(1)).externalProcess(any[Seq[String]], any[ProcessLogger])
        verify(sparkProcess, times(1)).tailedWriter(any[Int], any[Path])
        verify(sparkProcess, times(1)).untailedWriter(any[Path])
      }

      cleanUpJob(jobPaths)
    }

  }

  private def write(file: File, contents: String) = {
    val writer = new FileWriter(file)
    writer.write(contents)
    writer.flush()
    writer.close()
    file
  }

  private def cleanUpJob(jobPaths: JobPaths): Unit = jobPaths.workflowRoot.delete(true)

  private def prepareJob(wdlSource: WdlSource = helloWorldWdl, runtimeString: String = passOnStderr, inputFiles: Option[Map[String, WdlValue]] = None, isCluster: Boolean = false): TestJobDescriptor = {
    val backendWorkflowDescriptor = buildWorkflowDescriptor(wdl = wdlSource, inputs = inputFiles.getOrElse(Map.empty), runtime = runtimeString)
    val backendConfigurationDescriptor = if (isCluster) BackendConfigurationDescriptor(backendClusterConfig, ConfigFactory.load) else BackendConfigurationDescriptor(backendClientConfig, ConfigFactory.load)
    val jobDesc = jobDescriptorFromSingleCallWorkflow(backendWorkflowDescriptor, inputFiles.getOrElse(Map.empty))
    val jobPaths = if (isCluster) new JobPaths(backendWorkflowDescriptor, backendClusterConfig, jobDesc.key) else new JobPaths(backendWorkflowDescriptor, backendClientConfig, jobDesc.key)
    val executionDir = jobPaths.callRoot
    val stdout = Paths.get(executionDir.path.toString, "stdout")
    stdout.toString.toFile.createIfNotExists(false)
    val stderr = Paths.get(executionDir.path.toString, "stderr")
    stderr.toString.toFile.createIfNotExists(false)
    TestJobDescriptor(jobDesc, jobPaths, backendConfigurationDescriptor)
  }

  private case class TestJobDescriptor(jobDescriptor: BackendJobDescriptor, jobPaths: JobPaths, backendConfigurationDescriptor: BackendConfigurationDescriptor)

  trait MockWriter extends Writer {
    var closed = false

    override def close() = closed = true

    override def flush() = {}

    override def write(a: Array[Char], b: Int, c: Int) = {}
  }

  trait MockPathWriter extends PathWriter {
    override lazy val writer: Writer = new MockWriter {}
    override val path: Path = mock[Path]
  }
}