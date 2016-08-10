package cromwell.database.migration.metadata.table

import java.sql.{PreparedStatement, ResultSet}

import cromwell.database.migration.metadata.{MetadataMigration, MetadataStatement, MetadataStatementForCall, MetadataStatementForWorkflow}
import liquibase.database.jvm.JdbcConnection
import liquibase.exception.CustomChangeException
import wdl4s.types.{WdlPrimitiveType, WdlType}
import wdl4s.values._

import scala.util.{Failure, Success, Try}

class OutputSymbolTableMigration extends MetadataMigration {
  override protected def selectQuery: String = """
     SELECT SYMBOL.SCOPE, SYMBOL.NAME, MaxExecution.IDX, IO, WDL_TYPE,
     |  WDL_VALUE, WORKFLOW_EXECUTION_UUID, ExecutionId.EXECUTION_ID, REPORTABLE_RESULT,
     |  MaxExecution.MaxAttempt
     |FROM SYMBOL
     |  LEFT JOIN WORKFLOW_EXECUTION ON SYMBOL.WORKFLOW_EXECUTION_ID = WORKFLOW_EXECUTION.WORKFLOW_EXECUTION_ID # Joins the workflow UUID
     |  LEFT JOIN( # Joins max attempt for each execution
     |             SELECT CALL_FQN, IDX, WORKFLOW_EXECUTION_ID, MAX(EXECUTION.ATTEMPT) AS MaxAttempt
     |             FROM EXECUTION
     |             GROUP BY EXECUTION.CALL_FQN, EXECUTION.IDX, EXECUTION.WORKFLOW_EXECUTION_ID
     |           ) MaxExecution
     |    ON SYMBOL.SCOPE = MaxExecution.CALL_FQN
     |       AND SYMBOL.WORKFLOW_EXECUTION_ID = MaxExecution.WORKFLOW_EXECUTION_ID
     |       AND SYMBOL.`INDEX` = MaxExecution.IDX
     |  LEFT JOIN(SELECT EXECUTION_ID, CALL_FQN, IDX, WORKFLOW_EXECUTION_ID, ATTEMPT #Finds the Execution Id corresponding to the max attempt
     |            FROM EXECUTION
     |           ) ExecutionId
     |    ON MaxExecution.CALL_FQN = ExecutionId.CALL_FQN
     |       AND MaxExecution.WORKFLOW_EXECUTION_ID = ExecutionId.WORKFLOW_EXECUTION_ID
     |       AND MaxExecution.IDX = ExecutionId.IDX
     |       AND MaxExecution.MaxAttempt = ExecutionId.ATTEMPT
     |WHERE SYMBOL.IO = "OUTPUT";
     |""".stripMargin

  override protected def migrateRow(connection: JdbcConnection, collectors: Set[Int],
                                    statement: PreparedStatement, row: ResultSet, idx: Int): Unit = {
    // Try to coerce the value to a WdlValue
    val value = for {
      wdlTypeValue <- Try(row.getString("WDL_TYPE"))
      wdlType <- Try(WdlType.fromWdlString(wdlTypeValue))
      wdlValueValue <- Try(row.getString("WDL_VALUE"))

      wdlValue <- wdlType match {
        case p: WdlPrimitiveType => p.coerceRawValue(wdlValueValue)
        case o => Try(wdlType.fromWdlString(wdlValueValue))
      }
    } yield wdlValue

    value match {
      case Success(wdlValue) => processSymbol(statement, row, idx, collectors, wdlValue)
      case Failure(f) =>
        throw new CustomChangeException(s"Could not parse wdl value ${row.getString("WDL_VALUE")} of type ${row.getString("WDL_TYPE")}", f)
    }
  }

  def processSymbol(statement: PreparedStatement, row: ResultSet, idx: Int, collectors: Set[Int], wdlValue: WdlValue) = {
    val scope = row.getString("SCOPE")
    val name = row.getString("NAME")
    val index = row.getInt("IDX")

    if (!collectors.contains(row.getInt("EXECUTION_ID"))) {
      // Add outputs only to the last attempt
      val metadataStatementForCall = new MetadataStatementForCall(statement,
        row.getString("WORKFLOW_EXECUTION_UUID"),
        scope,
        index,
        row.getInt("MaxAttempt")
      )

      addWdlValue(s"outputs:$name", wdlValue, metadataStatementForCall)
    }

    // If it's a workflow output, also add it there
    if (row.getInt("REPORTABLE_RESULT") == 1 && index == -1) {
      val metadataStatementForWorkflow = new MetadataStatementForWorkflow(statement, row.getString("WORKFLOW_EXECUTION_UUID"))
      addWdlValue(s"outputs:$scope.$name", wdlValue, metadataStatementForWorkflow)
    }
  }

  /**
    * Add all necessary statements to the batch for the provided WdlValue.
    */
  private def addWdlValue(metadataKey: String, wdlValue: WdlValue, metadataStatementForCall: MetadataStatement): Unit = wdlValue match {
    case WdlArray(_, valueSeq) =>
      if (valueSeq.isEmpty) {
        metadataStatementForCall.addKeyValue(s"$metadataKey[]", null)
      } else {
        val zippedSeq = valueSeq.zipWithIndex
        zippedSeq.toList foreach { case (value, index) => addWdlValue(s"$metadataKey[$index]", value, metadataStatementForCall) }
      }
    case WdlMap(_, valueMap) =>
      if (valueMap.isEmpty) {
        metadataStatementForCall.addKeyValue(metadataKey, null)
      } else {
        valueMap.toList foreach { case (key, value) => addWdlValue(s"$metadataKey:${key.valueString}", value, metadataStatementForCall) }
      }
    case value =>
      metadataStatementForCall.addKeyValue(metadataKey, value)
  }

  override def getConfirmationMessage: String = "Symbol Table migration complete."
}
