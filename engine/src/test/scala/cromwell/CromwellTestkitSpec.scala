package cromwell

import java.nio.file.Paths
import java.util.UUID

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.testkit._
import com.typesafe.config.{Config, ConfigFactory}
import cromwell.CromwellTestkitSpec._
import cromwell.backend._
import cromwell.core.retry.{Retry, SimpleExponentialBackoff}
import cromwell.core.{WorkflowId, _}
import cromwell.database.obj.WorkflowMetadataKeys
import cromwell.engine.backend.BackendConfigurationEntry
import cromwell.engine.workflow.{WorkflowManagerActor, WorkflowStoreActor}
import cromwell.engine.workflow.WorkflowStore.{Submitted, WorkflowToStart}
import cromwell.engine.workflow.WorkflowStoreActor.WorkflowSubmittedToStore
import cromwell.server.WorkflowManagerSystem
import cromwell.services.MetadataQuery
import cromwell.services.MetadataServiceActor._
import cromwell.util.SampleWdl
import cromwell.webservice.PerRequest.RequestComplete
import cromwell.webservice.metadata.MetadataBuilderActor
import org.scalactic.Equality
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, Matchers, OneInstancePerTest, WordSpecLike}
import spray.http.StatusCode
import spray.json._
import wdl4s.Call
import wdl4s.expression.{NoFunctions, WdlStandardLibraryFunctions}
import wdl4s.types.{WdlArrayType, WdlMapType, WdlStringType, _}
import wdl4s.values.{WdlFile, WdlString, WdlValue, _}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps
import scala.reflect.ClassTag
import scala.util.matching.Regex
import scalaz.NonEmptyList

case class TestBackendLifecycleActorFactory(configurationDescriptor: BackendConfigurationDescriptor) extends BackendLifecycleActorFactory {
  override def workflowInitializationActorProps(workflowDescriptor: BackendWorkflowDescriptor,
                                                calls: Seq[Call]): Option[Props] = None

  override def jobExecutionActorProps(jobDescriptor: BackendJobDescriptor, initializationData: Option[BackendInitializationData]): Props = {
    throw new NotImplementedError("this is not implemented")
  }

  override def expressionLanguageFunctions(workflowDescriptor: BackendWorkflowDescriptor,
                                           jobKey: BackendJobDescriptorKey,
                                           initializationData: Option[BackendInitializationData]): WdlStandardLibraryFunctions = {
    NoFunctions
  }
}

case class OutputNotFoundException(outputFqn: String, actualOutputs: String) extends RuntimeException(s"Expected output $outputFqn was not found in: '$actualOutputs'")
case class LogNotFoundException(log: String) extends RuntimeException(s"Expected log $log was not found")

object CromwellTestkitSpec {
  val ConfigText =
    """
      |akka {
      |  loggers = ["akka.testkit.TestEventListener"]
      |  loglevel = "INFO"
      |  actor {
      |    debug {
      |       receive = on
      |    }
      |
      |    deployment {
      |      /WorkflowManagerActor/WorkflowLogCopyRouter {
      |        router = round-robin-pool
      |        nr-of-instances = 0
      |      }
      |    }
      |  }
      |  dispatchers {
      |    slow-actor-dispatcher {
      |      type = Dispatcher
      |      executor = "fork-join-executor"
      |    }
      |  }
      |  test {
      |    # Some of our tests fire off a message, then expect a particular event message within 3s (the default).
      |    # Especially on CI, the metadata test does not seem to be returning in time. So, overriding the timeouts
      |    # with slightly higher values. Alternatively, could also adjust the akka.test.timefactor only in CI.
      |    filter-leeway = 5s
      |    single-expect-default = 5s
      |    default-timeout = 10s
      |  }
      |}
      |
      |services {}
    """.stripMargin

  val TimeoutDuration = 60 seconds

  class TestWorkflowManagerSystem extends WorkflowManagerSystem {
    override protected def systemName: String = "test-system"
    override protected def newActorSystem() = ActorSystem(systemName, ConfigFactory.parseString(CromwellTestkitSpec.ConfigText))
    /**
      * Do NOT shut down the test actor system inside the normal flow.
      * The actor system will be externally shutdown outside the block.
      */
    override def shutdownActorSystem() = {}

    def shutdownTestActorSystem() = super.shutdownActorSystem()
  }

  /**
    * Loans a test actor system. NOTE: This should be run OUTSIDE of a wait block, never within one.
    */
  def withTestWorkflowManagerSystem[T](block: WorkflowManagerSystem => T): T = {
    val testWorkflowManagerSystem = new CromwellTestkitSpec.TestWorkflowManagerSystem
    try {
      block(testWorkflowManagerSystem)
    } finally {
      TestKit.shutdownActorSystem(testWorkflowManagerSystem.actorSystem, TimeoutDuration)
    }
  }

  /**
   * Wait for exactly one occurrence of the specified info pattern in the specified block.  The block is in its own
   * parameter list for usage syntax reasons.
   */
  def waitForInfo[T](pattern: String, occurrences: Int = 1)(block: => T)(implicit system: ActorSystem): T = {
    EventFilter.info(pattern = pattern, occurrences = occurrences).intercept {
      block
    }
  }

  /**
   * Wait for occurrence(s) of the specified warning pattern in the specified block.  The block is in its own parameter
   * list for usage syntax reasons.
   */
  def waitForWarning[T](pattern: String, occurrences: Int = 1)(block: => T)(implicit system: ActorSystem): T = {
    EventFilter.warning(pattern = pattern, occurrences = occurrences).intercept {
      block
    }
  }

  /**
   * Wait for occurrence(s) of the specified error pattern in the specified block.  The block is in its own parameter
   * list for usage syntax reasons.
   */
  def waitForError[T](pattern: String, occurrences: Int = 1)(block: => T)(implicit system: ActorSystem): T = {
    EventFilter.error(pattern = pattern, occurrences = occurrences).intercept {
      block
    }
  }

  /**
   * Akka TestKit appears to be unable to match errors generated by `log.error(Throwable, String)` with the normal
   * `EventFilter.error(...).intercept {...}` mechanism since `EventFilter.error` forces the use of a dummy exception
   * that never matches a real exception.  This method works around that problem by building an `ErrorFilter` more
   * explicitly to allow the caller to specify a `Throwable` class.
   */
  def waitForErrorWithException[T](pattern: String,
                                   throwableClass: Class[_ <: Throwable] = classOf[Throwable],
                                   occurrences: Int = 1)
                                  (block: => T)
                                  (implicit system: ActorSystem): T = {
    val regex = Right[String, Regex](pattern.r)
    ErrorFilter(throwableClass, source = None, message = regex, complete = false)(occurrences = occurrences).intercept {
      block
    }
  }

  /**
    * Special case for validating outputs. Used when the test wants to check that an output exists, but doesn't care what
    * the actual value was.
    */
  lazy val AnyValueIsFine: WdlValue = WdlString("Today you are you! That is truer than true! There is no one alive who is you-er than you!")

  implicit class EnhancedWorkflowManagerActor(val manager: TestActorRef[WorkflowManagerActor]) extends AnyVal {
    def submit(sources: WorkflowSourceFiles): WorkflowId = {
      val submitMessage = WorkflowStoreActor.SubmitWorkflow(sources)
      Await.result(manager.underlyingActor.workflowStore.ask(submitMessage)(TimeoutDuration), Duration.Inf).asInstanceOf[WorkflowSubmittedToStore].workflowId
    }
  }

  lazy val DefaultConfig = ConfigFactory.load
  lazy val DefaultLocalBackendConfig = ConfigFactory.parseString(
    """
      |  {
      |    root: "cromwell-executions"
      |
      |    filesystems {
      |      local {
      |        localization: [
      |          "hard-link", "soft-link", "copy"
      |        ]
      |      }
      |    }
      |  }
    """.stripMargin)

  lazy val DefaultLocalBackendConfigEntry = BackendConfigurationEntry(
    name = "local",
    lifecycleActorFactoryClass = "cromwell.TestBackendLifecycleActorFactory",
    DefaultLocalBackendConfig
  )

  lazy val JesBackendConfig = ConfigFactory.parseString(
    """
      |{
      |  // Google project
      |  project = "my-cromwell-workflows"
      |
      |  // Base bucket for workflow executions
      |  root = "gs://my-cromwell-workflows-bucket"
      |
      |  // Polling for completion backs-off gradually for slower-running jobs.
      |  // This is the maximum polling interval (in seconds):
      |  maximum-polling-interval = 600
      |
      |  // Optional Dockerhub Credentials. Can be used to access private docker images.
      |  dockerhub {
      |    // account = ""
      |    // token = ""
      |  }
      |
      |  genomics {
      |    // A reference to an auth defined in the `google` stanza at the top.  This auth is used to create
      |    // Pipelines and manipulate auth JSONs.
      |    auth = "service-account"
      |
      |    // Endpoint for APIs, no reason to change this unless directed by Google.
      |    endpoint-url = "https://genomics.googleapis.com/"
      |  }
      |
      |  filesystems {
      |    gcs {
      |      // A reference to a potentially different auth for manipulating files via engine functions.
      |      auth = "user-via-refresh"
      |    }
      |  }
      |}
    """.stripMargin)

  lazy val JesBackendConfigEntry = BackendConfigurationEntry(
    name = "JES",
    lifecycleActorFactoryClass = "cromwell.backend.impl.jes.JesBackendLifecycleActorFactory",
    JesBackendConfig
  )
}

abstract class CromwellTestkitSpec extends TestKit(new CromwellTestkitSpec.TestWorkflowManagerSystem().actorSystem)
  with DefaultTimeout with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll with ScalaFutures with OneInstancePerTest {

  val name = this.getClass.getSimpleName
  implicit val defaultPatience = PatienceConfig(timeout = Span(30, Seconds), interval = Span(100, Millis))
  implicit val ec = system.dispatcher

  // Allow to use shouldEqual between 2 WdlTypes while acknowledging for edge cases
  implicit val wdlTypeSoftEquality = new Equality[WdlType] {
    override def areEqual(a: WdlType, b: Any): Boolean = (a, b) match {
      case (WdlStringType | WdlFileType, WdlFileType | WdlStringType) => true
      case (arr1: WdlArrayType, arr2: WdlArrayType) => areEqual(arr1.memberType, arr2.memberType)
      case (map1: WdlMapType, map2: WdlMapType) => areEqual(map1.valueType, map2.valueType)
      case _ => a == b
    }
  }

  // Allow to use shouldEqual between 2 WdlValues while acknowledging for edge cases and checking for WdlType compatibilty
  implicit val wdlEquality = new Equality[WdlValue] {
    def fileEquality(f1: String, f2: String) = Paths.get(f1).getFileName == Paths.get(f2).getFileName

    override def areEqual(a: WdlValue, b: Any): Boolean = {
      val typeEquality = b match {
        case v: WdlValue => wdlTypeSoftEquality.areEqual(a.wdlType, v.wdlType)
        case _ => false
      }

      val valueEquality = (a, b) match {
        case (_: WdlFile, expectedFile: WdlFile) => fileEquality(a.valueString, expectedFile.valueString)
        case (_: WdlString, expectedFile: WdlFile) => fileEquality(a.valueString, expectedFile.valueString)
        case (array: WdlArray, expectedArray: WdlArray) =>
          (array.value.length == expectedArray.value.length) &&
            array.value.zip(expectedArray.value).map(Function.tupled(areEqual)).forall(identity)
        case (map: WdlMap, expectedMap: WdlMap) =>
          val mapped = map.value.map {
            case (k, v) => expectedMap.value.get(k).isDefined && areEqual(v, expectedMap.value(k))
          }

          (map.value.size == expectedMap.value.size) && mapped.forall(identity)
        case _ => a == b
      }

      typeEquality && valueEquality
    }
  }

  def startingCallsFilter[T](callNames: String*)(block: => T): T =
    waitForPattern(s"Starting calls: ${callNames.mkString("", ":NA:1, ", ":NA:1")}$$") {
      block
    }

  def waitForHandledMessage[T](named: String)(block: => T): T = {
    waitForHandledMessagePattern(s"^received handled message $named") {
      block
    }
  }

  def waitForHandledMessagePattern[T](pattern: String)(block: => T): T = {
    waitForInfo(pattern = pattern, occurrences = 1) {
      block
    }
  }

  /**
   * Performs the following steps:
   *
   * <ol>
   * <li> Sends the specified message to the implicitly passed `ActorRef` via an `ask`.
   * <li> Collects the `Future[Any]` response.
   * <li> Downcasts the `Future[Any]` to a `Future[M]`.
   * <li> Issues a blocking `Await.result` on the `Future`, yielding an `M`.
   * </ol>
   *
   */
  def messageAndWait[M: ClassTag](message: AnyRef)(implicit actorRef: ActorRef): M = {
    val futureAny = actorRef ? message
    Await.result(futureAny.mapTo[M], TimeoutDuration)
  }

  /**
   * Wait for exactly one occurrence of the specified info pattern in the specified block within a limited amount of
   * time. The block is in its own parameter list for usage syntax reasons.
   */
  def waitForPattern[T](pattern: String, occurrences: Int = 1)(block: => T): T = {
    within(TimeoutDuration) {
      waitForInfo(pattern, occurrences) {
        block
      }
    }
  }

  private def buildWorkflowManagerActor(config: Config) = {
    val workflowStore = system.actorOf(WorkflowStoreActor.props())
    TestActorRef(new WorkflowManagerActor(config, workflowStore), name = "WorkflowManagerActor")
  }

  def workflowSuccessFilter = EventFilter.info(pattern = "transition from FinalizingWorkflowState to WorkflowSucceededState", occurrences = 1)

  def runWdl(sampleWdl: SampleWdl,
             eventFilter: EventFilter = workflowSuccessFilter,
             runtime: String = "",
             workflowOptions: String = "{}",
             terminalState: WorkflowState = WorkflowSucceeded,
             config: Config = DefaultConfig)(implicit ec: ExecutionContext): Map[FullyQualifiedName, WdlValue] = {
    val wma = buildWorkflowManagerActor(config)
    val sources = WorkflowSourceFiles(sampleWdl.wdlSource(runtime), sampleWdl.wdlJson, workflowOptions)
    eventFilter.intercept {
      within(TimeoutDuration) {
        val workflowId = wma.submit(sources)
        verifyWorkflowState(wma, wma.underlyingActor.serviceRegistryActor, workflowId, terminalState)
        getWorkflowOutputsFromMetadata(workflowId, wma.underlyingActor.serviceRegistryActor)
      }
    }
  }

  def eventually[T](isFatal: Throwable => Boolean, maxRetries: Int = 3)(f: => T): T = {
    // Retry because we have no guarantee that all the metadata is there when the workflow finishes...
    Await.result(Retry.withRetry(
      () => Future(f),
      maxRetries = Some(maxRetries),
      isFatal = isFatal,
      backoff = SimpleExponentialBackoff(0.5 seconds, 0.5 second, 1D)), TimeoutDuration)
  }

  def runWdlAndAssertOutputs(sampleWdl: SampleWdl,
                             eventFilter: EventFilter,
                             expectedOutputs: Map[FullyQualifiedName, WdlValue],
                             runtime: String = "",
                             workflowOptions: String = "{}",
                             allowOtherOutputs: Boolean = true,
                             terminalState: WorkflowState = WorkflowSucceeded,
                             config: Config = DefaultConfig,
                             workflowManagerActor: Option[TestActorRef[WorkflowManagerActor]] = None)
                            (implicit ec: ExecutionContext): WorkflowId = {
    val workflowManager = workflowManagerActor.getOrElse(buildWorkflowManagerActor(config))
    val sources = sampleWdl.asWorkflowSources(runtime, workflowOptions)
    def isFatal(e: Throwable) = e match {
      case _: OutputNotFoundException => false
      case _ => true
    }
    val id = eventFilter.intercept {
      within(TimeoutDuration) {
        val workflowId = workflowManager.submit(sources)
        verifyWorkflowState(workflowManager, workflowManager.underlyingActor.serviceRegistryActor, workflowId, terminalState)

        eventually(isFatal) {
          val outputs = getWorkflowOutputsFromMetadata(workflowId, workflowManager.underlyingActor.serviceRegistryActor)
          val actualOutputNames = outputs.keys mkString ", "
          val expectedOutputNames = expectedOutputs.keys mkString " "

          expectedOutputs foreach { case (outputFqn, expectedValue) =>
            val actualValue = outputs.getOrElse(outputFqn, throw new OutputNotFoundException(outputFqn, actualOutputNames))
            if (expectedValue != AnyValueIsFine) actualValue shouldEqual expectedValue
          }
          if (!allowOtherOutputs) {
            outputs foreach { case (actualFqn, actualValue) =>
              val expectedValue = expectedOutputs.getOrElse(actualFqn, throw new RuntimeException(s"Actual output $actualFqn was not wanted in '$expectedOutputNames'"))
              if (expectedValue != AnyValueIsFine) actualValue shouldEqual expectedValue
            }
          }
          workflowId
        }
      }
    }

    if (workflowManagerActor.isEmpty) system.stop(workflowManager)
    id
  }

  def runWdlAndAssertLogs(sampleWdl: SampleWdl,
                          eventFilter: EventFilter,
                          expectedStdoutContent: String,
                          expectedStderrContent: String,
                          runtime: String = "",
                          workflowOptions: String = "{}",
                          allowOtherOutputs: Boolean = true,
                          terminalState: WorkflowState = WorkflowSucceeded,
                          config: Config = DefaultConfig,
                          workflowManagerActor: Option[TestActorRef[WorkflowManagerActor]] = None)
                         (implicit ec: ExecutionContext): WorkflowId = {
    val workflowManager = workflowManagerActor.getOrElse(buildWorkflowManagerActor(config))
    val sources = sampleWdl.asWorkflowSources(runtime, workflowOptions)
    def isFatal(e: Throwable) = e match {
      case _: LogNotFoundException => false
      case _ => true
    }
    eventFilter.intercept {
      within(TimeoutDuration) {
        val workflowId = workflowManager.submit(sources)
        verifyWorkflowState(workflowManager, workflowManager.underlyingActor.serviceRegistryActor, workflowId, terminalState)

        eventually(isFatal) {
          import better.files._
          val (stdout, stderr) = getFirstCallLogs(workflowId, workflowManager.underlyingActor.serviceRegistryActor)
          Paths.get(stdout).contentAsString shouldBe expectedStdoutContent
          Paths.get(stderr).contentAsString shouldBe expectedStderrContent
          workflowId
        }
      }
    }
  }

  def getWorkflowMetadata(workflowId: WorkflowId, serviceRegistryActor: ActorRef, key: Option[String] = None)(implicit ec: ExecutionContext): JsObject = {
    // MetadataBuilderActor sends its response to context.parent, so we can't just use an ask to talk to it here
    val message = GetMetadataQueryAction(MetadataQuery(workflowId, None, key, None, None))
    val parentProbe = TestProbe()

    TestActorRef(MetadataBuilderActor.props(serviceRegistryActor), parentProbe.ref, s"MetadataActor-${UUID.randomUUID()}") ! message
    val metadata = parentProbe.expectMsgPF(TimeoutDuration) {
      // Because of type erasure the scala compiler can't check that the RequestComplete generic type will be (StatusCode, JsObject), which would generate a warning
      // As long as Metadata sends back a JsObject this is safe
      case response: RequestComplete[(StatusCode, JsObject)] @unchecked => response.response._2
    }

    system.stop(parentProbe.ref)
    metadata
  }

  def verifyWorkflowState(wma: ActorRef, serviceRegistryActor: ActorRef, workflowId: WorkflowId, expectedState: WorkflowState)(implicit ec: ExecutionContext): Unit = {
    // Continuously check the state of the workflow until it is in a terminal state
    awaitCond(getWorkflowState(workflowId, serviceRegistryActor).isTerminal)
    // Now that it's complete verify that we ended up in the state we're expecting
    getWorkflowState(workflowId, serviceRegistryActor) should equal (expectedState)
  }

  def getWorkflowState(workflowId: WorkflowId, serviceRegistryActor: ActorRef)(implicit ec: ExecutionContext): WorkflowState = {
    val statusResponse = serviceRegistryActor.ask(GetStatus(workflowId))(TimeoutDuration).collect {
      case StatusLookupResponse(_, state) => state
      case StatusLookupNotFound(_) => WorkflowSubmitted
      case f => throw new RuntimeException(s"Unexpected status response for $workflowId: $f")
    }
    Await.result(statusResponse, Duration.Inf)
  }

  def getFirstCallLogs(workflowId: WorkflowId, serviceRegistryActor: ActorRef)(implicit ec: ExecutionContext): (String, String) = {
    val logsResponse = serviceRegistryActor.ask(GetLogs(workflowId)).collect {
      case LogsResponse(_, logs) =>
        val stdout = logs.find { _.key.key.endsWith("stdout") } flatMap { _.value map { _.value } } getOrElse { throw new LogNotFoundException("stdout") }
        val stderr = logs.find { _.key.key.endsWith("stderr") } flatMap { _.value map { _.value } } getOrElse { throw new LogNotFoundException("stderr") }
        (stdout, stderr)
      case f => throw new RuntimeException(s"Unexpected logs response: $f")
    }
    Await.result(logsResponse, Duration.Inf)
  }

  def getWorkflowOutputsFromMetadata(id: WorkflowId, serviceRegistryActor: ActorRef): Map[FullyQualifiedName, WdlValue] = {
    getWorkflowMetadata(id, serviceRegistryActor, None).getFields(WorkflowMetadataKeys.Outputs).toList match {
      case head::_ => head.asInstanceOf[JsObject].fields.map( x => (x._1, jsValueToWdlValue(x._2)))
      case _ => Map.empty
    }
  }

  private def jsValueToWdlValue(jsValue: JsValue): WdlValue = {
    jsValue match {
      case str: JsString => WdlString(str.value)
      case JsNumber(number) if number.scale == 0 => WdlInteger(number.intValue)
      case JsNumber(number) => WdlFloat(number.doubleValue)
      case JsBoolean(bool) => WdlBoolean(bool)
      case array: JsArray =>
        val valuesArray = array.elements.map(jsValueToWdlValue)
        if (valuesArray.isEmpty) WdlArray(WdlArrayType(WdlStringType), Seq.empty)
        else WdlArray(WdlArrayType(valuesArray.head.wdlType), valuesArray)
      case map: JsObject =>
        // TODO: currently assuming all keys are String. But that's not WDL-complete...
        val valuesMap: Map[WdlValue, WdlValue] = map.fields.map { case (fieldName, fieldValue) => (WdlString(fieldName), jsValueToWdlValue(fieldValue)) }
        if (valuesMap.isEmpty) WdlMap(WdlMapType(WdlStringType, WdlStringType), Map.empty)
        else WdlMap(WdlMapType(WdlStringType, valuesMap.head._2.wdlType), valuesMap)
    }
  }
}
