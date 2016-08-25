package cromwell.backend.impl.spark

import akka.actor.{ActorRef, Props}
import cromwell.backend.validation.RuntimeAttributesDefault
import cromwell.backend.validation.RuntimeAttributesKeys._
import cromwell.backend.impl.spark.SparkInitializationActor._
import cromwell.backend.{BackendInitializationData, BackendConfigurationDescriptor, BackendWorkflowDescriptor, BackendWorkflowInitializationActor}
import cromwell.core.WorkflowOptions
import wdl4s.types.WdlBooleanType
import wdl4s.values.WdlValue
import wdl4s.{WdlExpression, Call}

import scala.concurrent.Future
import scala.util.Try

object SparkInitializationActor {
  val SupportedKeys = Set(FailOnStderrKey, SparkRuntimeAttributes.ExecutorCoresKey, SparkRuntimeAttributes.ExecutorMemoryKey,
    SparkRuntimeAttributes.NumberOfExecutorsKey, SparkRuntimeAttributes.AppMainClassKey)

  def props(workflowDescriptor: BackendWorkflowDescriptor,
            calls: Seq[Call],
            configurationDescriptor: BackendConfigurationDescriptor,
            serviceRegistryActor: ActorRef): Props =
    Props(new SparkInitializationActor(workflowDescriptor, calls, configurationDescriptor, serviceRegistryActor))
}

class SparkInitializationActor(override val workflowDescriptor: BackendWorkflowDescriptor,
                               override val calls: Seq[Call],
                               override val configurationDescriptor: BackendConfigurationDescriptor,
                               override val serviceRegistryActor: ActorRef) extends BackendWorkflowInitializationActor {

  override protected def runtimeAttributeValidators: Map[String, (Option[WdlExpression]) => Boolean] = Map(
    FailOnStderrKey -> wdlTypePredicate(valueRequired = false, WdlBooleanType.isCoerceableFrom),
    SparkRuntimeAttributes.AppMainClassKey -> wdlTypePredicate(valueRequired = true, WdlBooleanType.isCoerceableFrom),
    SparkRuntimeAttributes.NumberOfExecutorsKey -> wdlTypePredicate(valueRequired = false, WdlBooleanType.isCoerceableFrom),
    SparkRuntimeAttributes.ExecutorMemoryKey -> wdlTypePredicate(valueRequired = false, WdlBooleanType.isCoerceableFrom),
    SparkRuntimeAttributes.ExecutorCoresKey -> wdlTypePredicate(valueRequired = false, WdlBooleanType.isCoerceableFrom)
  )

  /**
    * Abort all initializations.
    */
  override def abortInitialization(): Unit = throw new UnsupportedOperationException("aborting initialization is not supported")

  /**
    * A call which happens before anything else runs
    */
  override def beforeAll(): Future[Option[BackendInitializationData]] = Future.successful(None)


  /**
    * A call which happens before anything else runs
    */
  override def validate(): Future[Unit] = {
    Future {
      calls foreach { call =>
        val runtimeAttributes = call.task.runtimeAttributes.attrs
        val notSupportedAttributes = runtimeAttributes filterKeys {
          !SupportedKeys.contains(_)
        }
        if (notSupportedAttributes.nonEmpty) {
          val notSupportedAttrString = notSupportedAttributes.keys mkString ", "
          log.warning(s"Key/s [$notSupportedAttrString] is/are not supported by SparkBackend. Unsupported attributes will not be part of jobs executions.")
        }
      }
    }
  }

  override protected def coerceDefaultRuntimeAttributes(options: WorkflowOptions): Try[Map[String, WdlValue]] = {
    RuntimeAttributesDefault.workflowOptionsDefault(options, SparkRuntimeAttributes.coercionMap)
  }
}
