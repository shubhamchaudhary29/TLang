package dev.tlang.runtime.database;

import dev.tlang.interpreter.RuntimeCollections;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Resource-safe JDBC statement execution shared by both providers. */
final class JdbcQueries {
    private JdbcQueries() {}

    static List<Object> query(
            Connection connection,
            String sql,
            List<?> parameters,
            int timeoutSeconds,
            SqlParameters.Dialect dialect,
            String providerName,
            Function<SQLException, DatabaseFailure> errors) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeoutSeconds);
            SqlParameters.bind(statement, sql, parameters, dialect);
            try (ResultSet resultSet = statement.executeQuery()) {
                ResultSetMetaData metadata = resultSet.getMetaData();
                int columns = metadata.getColumnCount();
                List<Object> rows = RuntimeCollections.newList();
                while (resultSet.next()) {
                    Map<String, Object> row = RuntimeCollections.newMap();
                    for (int index = 1; index <= columns; index++) {
                        String label = metadata.getColumnLabel(index);
                        if (row.containsKey(label)) {
                            throw new DatabaseFailure(
                                "Query returned duplicate column label '" + label + "'. Use SQL aliases.");
                        }
                        row.put(label, SqlParameters.toTlang(
                            readValue(resultSet, metadata, index), providerName));
                    }
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException error) {
            throw errors.apply(error);
        }
    }

    private static Object readValue(
            ResultSet resultSet, ResultSetMetaData metadata, int index) throws SQLException {
        int jdbcType = metadata.getColumnType(index);
        String typeName = metadata.getColumnTypeName(index);
        if (jdbcType == Types.TIMESTAMP_WITH_TIMEZONE || "timestamptz".equalsIgnoreCase(typeName)) {
            return resultSet.getObject(index, java.time.OffsetDateTime.class);
        }
        if (jdbcType == Types.TIME_WITH_TIMEZONE || "timetz".equalsIgnoreCase(typeName)) {
            return resultSet.getObject(index, java.time.OffsetTime.class);
        }
        return resultSet.getObject(index);
    }

    static int execute(
            Connection connection,
            String sql,
            List<?> parameters,
            int timeoutSeconds,
            SqlParameters.Dialect dialect,
            Function<SQLException, DatabaseFailure> errors) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeoutSeconds);
            SqlParameters.bind(statement, sql, parameters, dialect);
            return statement.executeUpdate();
        } catch (SQLException error) {
            throw errors.apply(error);
        }
    }
}
