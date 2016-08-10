package cromwell.database.migration.metadata.table

import java.sql.{PreparedStatement, ResultSet}

import cromwell.database.migration.metadata.MetadataStatementForCall
import wdl4s.values._

class CallOutputSymbolTableMigration extends SymbolTableMigration {
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

  override def processSymbol(statement: PreparedStatement, row: ResultSet, idx: Int, wdlValue: WdlValue) = {
    val scope = row.getString("SCOPE")
    val name = row.getString("NAME")
    val index = row.getInt("IDX")

    // Add outputs only to the last attempt
    val metadataStatementForCall = new MetadataStatementForCall(statement,
      row.getString("WORKFLOW_EXECUTION_UUID"),
      scope,
      index,
      row.getInt("MaxAttempt")
    )

    addWdlValue(s"outputs:$name", wdlValue, metadataStatementForCall)
  }

  override def getConfirmationMessage: String = "Call outputs from Symbol Table migration complete."
}
