package cromwell.database

import java.sql.ResultSet

package object migration {
  class ResultSetIterator(rs: ResultSet) extends Iterator[ResultSet] {
    def hasNext: Boolean = rs.next()
    def next(): ResultSet = rs
  }
}
