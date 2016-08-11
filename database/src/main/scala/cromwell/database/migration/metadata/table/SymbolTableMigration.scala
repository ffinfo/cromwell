package cromwell.database.migration.metadata.table

import java.io.{IOException, ByteArrayInputStream}
import java.sql.{PreparedStatement, ResultSet}
import java.util.zip.GZIPInputStream

import cromwell.database.migration.metadata.{MetadataMigration, MetadataStatement}
import liquibase.database.jvm.JdbcConnection
import liquibase.exception.CustomChangeException
import org.apache.commons.codec.binary.Base64
import org.apache.commons.io.IOUtils
import wdl4s.types.{WdlPrimitiveType, WdlType}
import wdl4s.values._

import scala.util.{Failure, Success, Try}

abstract class SymbolTableMigration extends MetadataMigration {
  override protected def migrateRow(connection: JdbcConnection, statement: PreparedStatement, row: ResultSet, idx: Int): Unit = {
    // Try to coerce the value to a WdlValue
    val value = for {
      wdlTypeValue <- Try(row.getString("WDL_TYPE"))
      wdlType <- Try(WdlType.fromWdlString(wdlTypeValue))
      wdlValueValue <- Try(row.getString("WDL_VALUE"))
      inflated <- inflate(wdlValueValue)

      wdlValue <- wdlType match {
        case p: WdlPrimitiveType => p.coerceRawValue(inflated)
        case o => Try(wdlType.fromWdlString(inflated))
      }
    } yield wdlValue

    value match {
      case Success(wdlValue) => processSymbol(statement, row, idx, wdlValue)
      case Failure(f) =>
        throw new CustomChangeException(s"Could not parse wdl value ${row.getString("WDL_VALUE")} of type ${row.getString("WDL_TYPE")}", f)
    }
  }

  def processSymbol(statement: PreparedStatement, row: ResultSet, idx: Int, wdlValue: WdlValue): Unit

  def inflate(value: String) = {
    Try {
      IOUtils.toString(new GZIPInputStream(new ByteArrayInputStream(Base64.decodeBase64(value))))
    } recover {
      case e: IOException => value
    }
  }

  /**
    * Add all necessary statements to the batch for the provided WdlValue.
    */
  protected final def addWdlValue(metadataKey: String, wdlValue: WdlValue, metadataStatementForCall: MetadataStatement): Unit = wdlValue match {
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
}
