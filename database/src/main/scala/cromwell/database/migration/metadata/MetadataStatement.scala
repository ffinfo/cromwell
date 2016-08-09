package cromwell.database.migration.metadata

import java.sql.{Types, PreparedStatement, Timestamp}
import java.time.{ZoneOffset, OffsetDateTime}
import java.time.format.DateTimeFormatter

import cromwell.database.SqlConverters._
import liquibase.database.jvm.JdbcConnection
import org.slf4j.LoggerFactory
import wdl4s.values.{WdlBoolean, WdlFloat, WdlInteger, WdlValue}

object MetadataStatement {
  val workflowIdIdx = 1
  val keyIdx = 2
  val callFqnIdx = 3
  val callIndexIdx = 4
  val callAttemptIdx = 5
  val valueIdx = 6
  val timestampIdx = 7
  val valueTypeIdx = 8

  def makeStatement(connection: JdbcConnection) = connection.prepareStatement(
    """
      |INSERT INTO METADATA_JOURNAL
      |(WORKFLOW_EXECUTION_UUID, METADATA_KEY, METADATA_CALL_FQN, METADATA_CALL_INDEX, METADATA_CALL_ATTEMPT, METADATA_VALUE, METADATA_TIMESTAMP, METADATA_VALUE_TYPE)
      |VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    """.stripMargin)
}

trait MetadataStatement {
  def addKeyValue(key: String, value: Any): Unit
  def addEmptyValue(key: String): Unit
}

class MetadataStatementForWorkflow(preparedStatement: PreparedStatement, workflowId: String) extends MetadataStatement {
  val offsetDateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZZZZZ")
  val logger = LoggerFactory.getLogger("LiquibaseMetadataMigration")
  val dawn = OffsetDateTime.of(0, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toSystemTimestamp

  private def toTimestamp(timestamp: Timestamp) = timestamp.toSystemOffsetDateTime.format(offsetDateTimeFormatter)

  private def metadataType(value: Any) = {
    value match {
      case WdlInteger(i) => "int"
      case WdlFloat(f) => "number"
      case WdlBoolean(b) => "boolean"
      case value: WdlValue => "string"
      case _: Int | Long => "int"
      case _: Double | Float => "number"
      case _: Boolean => "boolean"
      case _ =>"string"
    }
  }

  private def metadataValue(value: Any) = {
    value match {
      case v: WdlValue  => v.valueString
      case v: Timestamp => toTimestamp(v)
      case v => v.toString
    }
  }

  protected def setStatement() = {
    preparedStatement.setString(MetadataStatement.workflowIdIdx, workflowId)
    preparedStatement.setNull(MetadataStatement.callFqnIdx, Types.VARCHAR)
    preparedStatement.setNull(MetadataStatement.callIndexIdx, Types.INTEGER)
    preparedStatement.setNull(MetadataStatement.callAttemptIdx, Types.INTEGER)
  }

  protected def addDataAndBatch(key: String, value: Any) = {
    preparedStatement.setString(MetadataStatement.keyIdx, key)

    // Set the value and type
    value match {
      case null =>
        preparedStatement.setNull(MetadataStatement.valueIdx, Types.VARCHAR)
        preparedStatement.setNull(MetadataStatement.valueTypeIdx, Types.VARCHAR) // Null values have null type
      case v =>
        preparedStatement.setString(MetadataStatement.valueIdx, metadataValue(value))
        preparedStatement.setString(MetadataStatement.valueTypeIdx, metadataType(value))
    }

    preparedStatement.addBatch()
  }

  private def add(key: String, value: Any, errorMessage: String) = try {
    setStatement()
    addDataAndBatch(key, value)
  } catch {
    case t: Throwable => logger.warn(errorMessage, t)
  }

  /** Adds a non-null value to the metadata journal. */
  override def addKeyValue(key: String, value: Any) = {
    if (value != null) {
      preparedStatement.setTimestamp(MetadataStatement.timestampIdx, OffsetDateTime.now().toSystemTimestamp)
      add(key, value, s"Failed to migrate metadata value $value with key $key for workflow $workflowId")
    }
  }

  override def addEmptyValue(key: String): Unit = {
    preparedStatement.setTimestamp(MetadataStatement.timestampIdx, dawn)
    add(key, null, s"Failed to add empty value with key $key for workflow $workflowId")
  }
}

class MetadataStatementForCall(preparedStatement: PreparedStatement, workflowId: String, callFqn: String, index: Int, attempt: Int) extends MetadataStatementForWorkflow(preparedStatement, workflowId) {
  override def setStatement() = {
    preparedStatement.setString(MetadataStatement.workflowIdIdx, workflowId)
    preparedStatement.setString(MetadataStatement.callFqnIdx, callFqn)
    preparedStatement.setInt(MetadataStatement.callIndexIdx, index)
    preparedStatement.setInt(MetadataStatement.callAttemptIdx, attempt)
  }
}