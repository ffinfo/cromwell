package cromwell.database.liquibase

import java.sql.Connection

import liquibase.database.DatabaseConnection
import liquibase.database.jvm.{HsqlConnection, JdbcConnection}

sealed trait MultiConnection extends DatabaseConnection {
  def otherConnection: Connection
  abstract override def close() = {
    otherConnection.close()
    super.close()
  }
}

case class MultiJdbcConnection(mainConnection: Connection, val otherConnection: Connection) extends JdbcConnection(mainConnection) with MultiConnection
case class MultiHsqlConnection(mainConnection: Connection, val otherConnection: Connection) extends HsqlConnection(mainConnection) with MultiConnection