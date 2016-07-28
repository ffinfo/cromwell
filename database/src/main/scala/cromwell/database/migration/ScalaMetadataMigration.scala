package cromwell.database.migration

import java.sql.{PreparedStatement, ResultSet}

import cromwell.database.SqlConverters._
import liquibase.change.custom.CustomTaskChange
import liquibase.database.Database
import liquibase.database.jvm.JdbcConnection
import liquibase.exception.ValidationErrors
import liquibase.resource.ResourceAccessor


class ResultSetIterator(rs: ResultSet) extends Iterator[ResultSet] {
  def hasNext: Boolean = rs.next()
  def next(): ResultSet = rs
}

object InsertMetadataStatement {
  val workflowIdIdx = 1
  val keyIdx = 2
  val callFqnIdx = 3
  val callIndexIdx = 4
  val callAttemptIdx = 5
  val valueIdx = 6
  val valueTypeIdx = 7

  def makeStatement(connection: JdbcConnection) = connection.prepareStatement(
    """
      |INSERT INTO METADATA_JOURNAL
      |(WORKFLOW_EXECUTION_UUID, METADATA_KEY, METADATA_CALL_FQN, METADATA_CALL_INDEX, METADATA_CALL_ATTEMPT, METADATA_VALUE, METADATA_TIMESTAMP, METADATA_VALUE_TYPE)
      |VALUES (?, ?, ?, ?, ?, ?, NOW(), ?)
    """.stripMargin)
}

class MetadataStatementForWorkflow(preparedStatement: PreparedStatement, workflowId: String) {

  protected def addDataAndBatch(key: String, value: String, valueType: String = "string") = {
    preparedStatement.setString(InsertMetadataStatement.keyIdx, key)
    preparedStatement.setString(InsertMetadataStatement.valueIdx, value)
    preparedStatement.setString(InsertMetadataStatement.valueTypeIdx, valueType)
    preparedStatement.addBatch()
  }

  def addKeyValueType(key: String, value: String, valueType: String = "string") = {
    preparedStatement.setString(InsertMetadataStatement.workflowIdIdx, workflowId)
    preparedStatement.setString(InsertMetadataStatement.callFqnIdx, null)
    preparedStatement.setString(InsertMetadataStatement.callIndexIdx, null)
    preparedStatement.setString(InsertMetadataStatement.callAttemptIdx, null)

    addDataAndBatch(key, value, valueType)
  }
}

class MetadataStatementForCall(preparedStatement: PreparedStatement, workflowId: String, callFqn: String, index: Int, attempt: Int) extends MetadataStatementForWorkflow(preparedStatement, workflowId) {
  override def addKeyValueType(key: String, value: String, valueType: String = "string") = {
    preparedStatement.setString(InsertMetadataStatement.workflowIdIdx, workflowId)
    preparedStatement.setString(InsertMetadataStatement.callFqnIdx, callFqn)
    preparedStatement.setInt(InsertMetadataStatement.callIndexIdx, index)
    preparedStatement.setInt(InsertMetadataStatement.callAttemptIdx, attempt)

    addDataAndBatch(key, value, valueType)
  }
}

class ScalaMetadataMigration extends CustomTaskChange {

  private var resourceAccessor: ResourceAccessor = null

  override def execute(database: Database): Unit = {
    val dbConn = database.getConnection.asInstanceOf[JdbcConnection]
    try {
      dbConn.setAutoCommit(false)

      migrateWorkflowExecutionTable(dbConn)
      migrateExecutionTable(dbConn)
    } catch {
      case _: Throwable =>
    }
  }

  def migrateTable(connection: JdbcConnection, query: String, mapper: (PreparedStatement, ResultSet) => Unit) = {
    val executionDataResultSet = connection.createStatement().executeQuery(query)
    val metadataStatement = InsertMetadataStatement.makeStatement(connection)

    val executionIterator = new ResultSetIterator(executionDataResultSet)

    executionIterator.zipWithIndex foreach {
      case (result, idx) =>
        mapper(metadataStatement, result)
        if (idx % 100 == 0 || !executionIterator.hasNext) metadataStatement.executeBatch()
    }

    connection.commit()
  }

  def migrateExecutionTable(connection: JdbcConnection) = {
    val executionTableQuery = """
        SELECT CALL_FQN, EXECUTION.STATUS, IDX, ATTEMPT, RC, EXECUTION.START_DT, EXECUTION.END_DT, BACKEND_TYPE, ALLOWS_RESULT_REUSE, WORKFLOW_EXECUTION_UUID
         FROM EXECUTION, WORKFLOW_EXECUTION
         WHERE EXECUTION.WORKFLOW_EXECUTION_ID = WORKFLOW_EXECUTION.WORKFLOW_EXECUTION_ID AND CALL_FQN NOT LIKE "%$%";
                                 """

    migrateTable(connection, executionTableQuery, addExecutionData)
  }

  def migrateWorkflowExecutionTable(connection: JdbcConnection) = {
    val workflowExecutionTableQuery = """
        SELECT WORKFLOW_EXECUTION_UUID, STATUS, START_DT, END_DT, WORKFLOW_NAME
        | FROM WORKFLOW_EXECUTION;
      """.stripMargin

    migrateTable(connection, workflowExecutionTableQuery, addWorkflowExecutionData)
  }

  def migrateSymbolTable(connection: JdbcConnection) = {
    val symbolTableQuery = """
    SELECT SYMBOL.SCOPE, SYMBOL.NAME, SYMBOL.INDEX, IO, WDL_TYPE, WDL_VALUE, WORKFLOW_EXECUTION_UUID, SYMBOL.WORKFLOW_EXECUTION_ID
    | FROM SYMBOL, WORKFLOW_EXECUTION
    | WHERE SYMBOL.WORKFLOW_EXECUTION_ID = WORKFLOW_EXECUTION.WORKFLOW_EXECUTION_ID;""".stripMargin

    migrateTable(connection, symbolTableQuery, addWorkflowExecutionData)
  }



  def addWorkflowExecutionData(statement: PreparedStatement, result: ResultSet) = {
    val startDt = result.getTimestamp("START_DT")

    val metadataStatement = new MetadataStatementForWorkflow(statement, result.getString("WORKFLOW_EXECUTION_UUID"))

    metadataStatement.addKeyValueType("submission", startDt.toSystemOffsetDateTime.toString)
    metadataStatement.addKeyValueType("start", startDt.toSystemOffsetDateTime.toString)
    metadataStatement.addKeyValueType("end", result.getTimestamp("END_DT").toSystemOffsetDateTime.toString)
    metadataStatement.addKeyValueType("status", result.getString("STATUS"))
    metadataStatement.addKeyValueType("workflowName", result.getString("WORKFLOW_NAME"))
  }

  def addExecutionData(statement: PreparedStatement, result: ResultSet) = {
    val metadataStatement = new MetadataStatementForCall(statement,
      result.getString("WORKFLOW_EXECUTION_UUID"),
      result.getString("CALL_FQN"),
      result.getInt("IDX"),
      result.getInt("ATTEMPT")
    )

    metadataStatement.addKeyValueType("start", result.getTimestamp("START_DT").toSystemOffsetDateTime.toString)
    metadataStatement.addKeyValueType("backend", result.getString("BACKEND_TYPE"))
    metadataStatement.addKeyValueType("end", result.getTimestamp("END_DT").toSystemOffsetDateTime.toString)
    metadataStatement.addKeyValueType("executionStatus", result.getString("STATUS"))
    metadataStatement.addKeyValueType("returnCode", result.getString("RC"))
    metadataStatement.addKeyValueType("cache:allowResultReuse", result.getString("ALLOWS_RESULT_REUSE"))
  }

  def addSymbolData(connection: JdbcConnection, statement: PreparedStatement, result: ResultSet) = {
    val callFqn = result.getString("SCOPE")
    val name = result.getString("NAME")
    val maxAtemptResultSet = connection.createStatement().executeQuery(
      s"""
         |SELECT MAX(ATTEMPT) as max
         |FROM EXECUTION
         |WHERE CALL_FQN = $callFqn AND WORKFLOW_EXECUTION_ID = ${result.getString("WORKFLOW_EXECUTION_ID")}
      """.stripMargin
    )

    val metadataStatement = new MetadataStatementForCall(statement,
      result.getString("WORKFLOW_EXECUTION_UUID"),
      callFqn,
      result.getInt("INDEX"),
      maxAtemptResultSet.getInt("max")
    )



    if (result.getString("IO") == "input") {
      metadataStatement.addKeyValueType(s"inputs:", result.getTimestamp("START_DT").toSystemOffsetDateTime.toString)
    }
    metadataStatement.addKeyValueType("backend", result.getString("BACKEND_TYPE"))
    metadataStatement.addKeyValueType("end", result.getTimestamp("END_DT").toSystemOffsetDateTime.toString)
    metadataStatement.addKeyValueType("executionStatus", result.getString("STATUS"))
    metadataStatement.addKeyValueType("returnCode", result.getString("RC"))
    metadataStatement.addKeyValueType("cache:allowResultReuse", result.getString("ALLOWS_RESULT_REUSE"))
  }

  override def setUp(): Unit = { }

  override def getConfirmationMessage: String = "Metadata migration complete."

  override def validate(database: Database): ValidationErrors = {
    new ValidationErrors
  }

  override def setFileOpener(resourceAccessor: ResourceAccessor): Unit = {
    this.resourceAccessor = resourceAccessor
  }
}
