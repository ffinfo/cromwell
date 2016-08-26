package cromwell.backend.impl.spark

import java.nio.file.Path

import com.typesafe.scalalogging.StrictLogging
import cromwell.core.{TailedWriter, UntailedWriter}
import cromwell.core.PathFactory.EnhancedPath

import scala.sys.process._
import better.files._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

object SparkCommands {
  val SPARK_SUBMIT = "spark-submit"
  val SEPARATOR = "--"
  val EXECUTOR_CORES = "total-executor-cores"
  val EXECUTOR_MEMORY = "executor-memory"
  val APP_MAIN_CLASS = "class"
  val MASTER = "master"
  val DEPLOY_MODE = "deploy-mode"
  val SPARK_APP_WITH_ARGS = "spark_app_with_args"
  val MEMORY_UNIT = "G" //Accepts only GB
  val SHELL_CMD = "sh"
  val PIPELINE = "&>"
}

class SparkCommands extends StrictLogging {
  import SparkCommands._
  import SparkClusterConstants._
  /**
    * Writes the script file containing the user's command from the WDL as well
    * as some extra shell code for monitoring jobs
    */
  def writeScript(instantiatedCommand: String, filePath: Path, containerRoot: Path) = {
    filePath.write(
      s"""#!/bin/sh
          |cd $containerRoot
          |$instantiatedCommand
          |echo $$? > rc
          |""".stripMargin)
  }

  def sparkSubmitCommand(attributes: Map[String, Any]): String = {
    val stringBuilder = StringBuilder.newBuilder
    val commandPlaceHolder = " %s%s %s "
    val sparkHome = Try(sys.env("SPARK_HOME")) match {
      case Success(s) => Some(s)
      case Failure(ex) => logger.warn(s"Spark home does not exist picking up default command")
        None
    }

    val sparkSubmit = sparkHome
      .map(p => String.format("%s%s", p, s"/bin/$SPARK_SUBMIT"))
      .getOrElse(SPARK_SUBMIT)

    attributes.foreach {
      case (key, _) => attributes.get(key) match {
        case Some (v) => commandPlaceHolder.format (SEPARATOR, key, v)
        case None => logger.warn (s" key: $key doesn't exit spark should pick it")
      }
    }

    val sparkCmd = stringBuilder.append(sparkSubmit)
      .append(commandPlaceHolder.format(SEPARATOR, MASTER, attributes(MASTER)))
      .append(commandPlaceHolder.format(SEPARATOR, DEPLOY_MODE, attributes(DEPLOY_MODE)))
      .append(commandPlaceHolder.format(SEPARATOR, APP_MAIN_CLASS, attributes(APP_MAIN_CLASS)))
      .append(commandPlaceHolder.format(SEPARATOR, EXECUTOR_CORES, attributes(EXECUTOR_CORES)))
      .append(commandPlaceHolder.format(SEPARATOR, EXECUTOR_MEMORY, "%s%s".format(attributes(EXECUTOR_MEMORY), MEMORY_UNIT)))
      .append(attributes(SPARK_APP_WITH_ARGS)).toString
    logger.debug(s"spark command is : $sparkCmd")
    sparkCmd
  }
}

trait SparkProcess {
  private val stdout = new StringBuilder
  private val stderr = new StringBuilder

  def processLogger: ProcessLogger = ProcessLogger(stdout append _, stderr append _)

  def processStdout: String = stdout.toString().trim

  def processStderr: String = stderr.toString().trim

  def commandList(command: String): Seq[String] = Seq("/bin/bash", command)

  def untailedWriter(path: Path): UntailedWriter = path.untailed

  def tailedWriter(limit: Int, path: Path): TailedWriter = path.tailed(limit)

  def externalProcess(cmdList: Seq[String], processLogger: ProcessLogger = processLogger): Process = cmdList.run(processLogger)
}