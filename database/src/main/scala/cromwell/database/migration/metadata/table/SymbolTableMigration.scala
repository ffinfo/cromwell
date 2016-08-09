package cromwell.database.migration.metadata.table

import java.sql.{PreparedStatement, ResultSet}

import cromwell.database.migration.metadata.{MetadataStatement, MetadataStatementForCall, MetadataStatementForWorkflow, MetadataMigration}
import liquibase.database.jvm.JdbcConnection
import liquibase.exception.CustomChangeException
import wdl4s.types.{WdlPrimitiveType, WdlType}
import wdl4s.values._

import scala.util.{Failure, Success, Try}

class SymbolTableMigration extends MetadataMigration {
  override protected def selectQuery: String = """
     |SELECT SYMBOL.SCOPE, SYMBOL.NAME, MaxExecution.IDX, IO, WDL_TYPE,
     |  WDL_VALUE, WORKFLOW_EXECUTION_UUID, SYMBOL.WORKFLOW_EXECUTION_ID, REPORTABLE_RESULT,
     |  MaxExecution.MaxAttempt, MaxExecution.EXECUTION_ID
     |FROM SYMBOL
     |  LEFT JOIN WORKFLOW_EXECUTION ON SYMBOL.WORKFLOW_EXECUTION_ID = WORKFLOW_EXECUTION.WORKFLOW_EXECUTION_ID
     |  LEFT JOIN(SELECT CALL_FQN, IDX, WORKFLOW_EXECUTION_ID, EXECUTION_ID, MAX(EXECUTION.ATTEMPT) AS MaxAttempt
     |            FROM EXECUTION
     |            GROUP BY EXECUTION.CALL_FQN, EXECUTION.IDX, EXECUTION.WORKFLOW_EXECUTION_ID) MaxExecution
     |    ON SYMBOL.SCOPE = MaxExecution.CALL_FQN
     |       AND SYMBOL.WORKFLOW_EXECUTION_ID = MaxExecution.WORKFLOW_EXECUTION_ID;
     |""".stripMargin

  override protected def migrateRow(connection: JdbcConnection, collectors: Set[Int],
                                    statement: PreparedStatement, row: ResultSet, idx: Int): Unit = {
    val scope = row.getString("SCOPE")
    val name = row.getString("NAME")
    val executionId = row.getString("EXECUTION_ID")

    // If it's a workflow input or output, we'll need a workflow level insert statement
    lazy val metadataStatementForWorkflow = new MetadataStatementForWorkflow(statement, row.getString("WORKFLOW_EXECUTION_UUID"))

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
      case Success(wdlValue) =>
        val keyIO = if (row.getString("IO") == "INPUT") "inputs" else "outputs"

        if (executionId != null) {
          // Call scoped
          val metadataStatementForCall = new MetadataStatementForCall(statement,
            row.getString("WORKFLOW_EXECUTION_UUID"),
            scope,
            row.getInt("IDX"),
            row.getInt("MaxAttempt")
          )

          if (!collectors.contains(executionId.toInt)) {
            addWdlValue(s"$keyIO:$name", wdlValue, metadataStatementForCall)
          }
        } else if (!scope.contains('.')) { // Only add workflow level inputs with single-word FQN
          // Workflow scoped
          addWdlValue(s"$keyIO:$scope.$name", wdlValue, metadataStatementForWorkflow)
        }

        // If it's a workflow output, also add it there
        if (row.getInt("REPORTABLE_RESULT") == 1) {
          addWdlValue(s"outputs:$scope.$name", wdlValue, metadataStatementForWorkflow)
        }
      case Failure(f) =>
        throw new CustomChangeException(s"Could not parse wdl value ${row.getString("WDL_VALUE")} of type ${row.getString("WDL_TYPE")}", f)
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
