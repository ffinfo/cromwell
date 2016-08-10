package cromwell.database.migration.metadata.table

import java.sql.{PreparedStatement, ResultSet}

import cromwell.database.migration.metadata.{MetadataMigration, MetadataStatement, MetadataStatementForCall, MetadataStatementForWorkflow}
import liquibase.database.jvm.JdbcConnection
import liquibase.exception.CustomChangeException
import wdl4s.types.{WdlPrimitiveType, WdlType}
import wdl4s.values._

import scala.util.{Failure, Success, Try}

class InputSymbolTableMigration extends MetadataMigration {
  override protected def selectQuery: String = """
   SELECT SYMBOL.SCOPE, SYMBOL.NAME, ex.IDX, ex.ATTEMPT, IO, WDL_TYPE,
   |  WDL_VALUE, WORKFLOW_EXECUTION_UUID, REPORTABLE_RESULT, ex.EXECUTION_ID, SYMBOL.WORKFLOW_EXECUTION_ID
   |FROM SYMBOL
   |  LEFT JOIN WORKFLOW_EXECUTION ON SYMBOL.WORKFLOW_EXECUTION_ID = WORKFLOW_EXECUTION.WORKFLOW_EXECUTION_ID # Joins the workflow UUID
   |  LEFT JOIN(
   |    SELECT CALL_FQN, IDX, ATTEMPT, WORKFLOW_EXECUTION_ID, EXECUTION_ID
   |    FROM EXECUTION
   |  ) ex
   |  ON SYMBOL.WORKFLOW_EXECUTION_ID = ex.WORKFLOW_EXECUTION_ID
   |  AND SYMBOL.SCOPE = ex.CALL_FQN
   |WHERE IO = 'INPUT';""".stripMargin

  override protected def migrateRow(connection: JdbcConnection, collectors: Set[Int],
                                    statement: PreparedStatement, row: ResultSet, idx: Int): Unit = if (!collectors.contains(row.getInt("EXECUTION_ID"))) {
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
    val name = row.getString("NAME")
    val scope = row.getString("SCOPE")
    val index = row.getString("IDX")
    val attempt = row.getInt("ATTEMPT")

    if (index != null) {
      // Call scoped
      val metadataStatementForCall = new MetadataStatementForCall(statement,
        row.getString("WORKFLOW_EXECUTION_UUID"),
        scope,
        row.getInt("IDX"),
        attempt
      )

      addWdlValue(s"inputs:$name", wdlValue, metadataStatementForCall)
    } else if (!scope.contains('.')) { // Only add workflow level inputs with single-word FQN
      // Workflow scoped
      val metadataStatementForWorkflow = new MetadataStatementForWorkflow(statement, row.getString("WORKFLOW_EXECUTION_UUID"))
      addWdlValue(s"inputs:$scope.$name", wdlValue, metadataStatementForWorkflow)
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
