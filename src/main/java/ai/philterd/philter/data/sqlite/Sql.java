/*
 *     Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.philterd.philter.data.sqlite;

import ai.philterd.philter.data.services.Listings;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

/** Statements over a connection, with Philter's values bound and read the same way everywhere. */
final class Sql {

    /** A column name a sort may name. Sort fields come from the API's whitelists, and are checked again here. */
    private static final Pattern COLUMN = Pattern.compile("[a-z_]+");

    private Sql() {
    }

    /** Reads one row into a value. */
    @FunctionalInterface
    interface Mapper<T> {
        T map(Row row) throws SQLException;
    }

    /** A row of a result, with Philter's values read the way they are bound. */
    static final class Row {

        private final ResultSet resultSet;

        Row(final ResultSet resultSet) {
            this.resultSet = resultSet;
        }

        String string(final String column) throws SQLException {
            return resultSet.getString(column);
        }

        long longValue(final String column) throws SQLException {
            return resultSet.getLong(column);
        }

        int intValue(final String column) throws SQLException {
            return resultSet.getInt(column);
        }

        Integer integer(final String column) throws SQLException {
            final int value = resultSet.getInt(column);
            return resultSet.wasNull() ? null : value;
        }

        double doubleValue(final String column) throws SQLException {
            return resultSet.getDouble(column);
        }

        boolean bool(final String column) throws SQLException {
            return resultSet.getInt(column) != 0;
        }

        Date date(final String column) throws SQLException {
            final long millis = resultSet.getLong(column);
            return resultSet.wasNull() ? null : new Date(millis);
        }

        byte[] bytes(final String column) throws SQLException {
            return resultSet.getBytes(column);
        }

    }

    private static PreparedStatement prepare(final Connection connection, final String sql, final Object... params)
            throws SQLException {
        final PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            final Object value = params[i];
            final int index = i + 1;
            if (value == null) {
                statement.setNull(index, Types.NULL);
            } else if (value instanceof Boolean b) {
                statement.setInt(index, b ? 1 : 0);
            } else if (value instanceof Date d) {
                statement.setLong(index, d.getTime());
            } else if (value instanceof Integer n) {
                statement.setInt(index, n);
            } else if (value instanceof Long n) {
                statement.setLong(index, n);
            } else if (value instanceof Double n) {
                statement.setDouble(index, n);
            } else if (value instanceof byte[] b) {
                statement.setBytes(index, b);
            } else {
                statement.setString(index, value.toString());
            }
        }
        return statement;
    }

    /** Runs a statement and returns how many rows it changed. */
    static int update(final Connection connection, final String sql, final Object... params) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, params)) {
            return statement.executeUpdate();
        }
    }

    /** Runs a query and maps every row. */
    static <T> List<T> query(final Connection connection, final String sql, final Mapper<T> mapper, final Object... params)
            throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, params);
             ResultSet resultSet = statement.executeQuery()) {
            final List<T> rows = new ArrayList<>();
            final Row row = new Row(resultSet);
            while (resultSet.next()) {
                rows.add(mapper.map(row));
            }
            return rows;
        }
    }

    /** Runs a query and maps its first row, or returns null when there is none. */
    static <T> T first(final Connection connection, final String sql, final Mapper<T> mapper, final Object... params)
            throws SQLException {
        final List<T> rows = query(connection, sql, mapper, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Runs a count query. */
    static long count(final Connection connection, final String sql, final Object... params) throws SQLException {
        final Long count = first(connection, sql, row -> row.longValue("n"), params);
        return count == null ? 0 : count;
    }

    /** The ORDER BY for a listing: its field, then insertion order, both in its direction. */
    static String orderBy(final Listings.Sort sort) {
        final String field = "_id".equals(sort.field()) ? "seq" : sort.field();
        if (!COLUMN.matcher(field).matches()) {
            throw new IllegalArgumentException("Not a sortable field: " + sort.field());
        }
        final String direction = sort.descending() ? " DESC" : " ASC";
        return " ORDER BY " + field + direction + ", seq" + direction;
    }

    /** A condition that a column contains the text, ignoring case, matched as plain text; null when there is none. */
    static String contains(final String column, final String q, final List<Object> params) {
        if (q == null || q.isBlank()) {
            return null;
        }
        params.add(q.trim());
        return "instr(lower(" + column + "), lower(?)) > 0";
    }

    /** The conditions, those that are not null, joined into a WHERE clause, or an empty string. */
    static String where(final String... conditions) {
        final List<String> present = new ArrayList<>();
        for (final String condition : conditions) {
            if (condition != null) {
                present.add("(" + condition + ")");
            }
        }
        return present.isEmpty() ? "" : " WHERE " + String.join(" AND ", present);
    }

    /** A page of a table's rows matching the WHERE clause, in the sort's order, and their total. */
    static <T> Listings.Page<T> page(final Connection connection, final String table, final String where,
                                     final List<Object> params, final Listings.Sort sort, final int offset,
                                     final int limit, final Mapper<T> mapper) throws SQLException {
        final List<Object> paged = new ArrayList<>(params);
        paged.add(limit);
        paged.add(offset);
        final List<T> items = query(connection, "SELECT * FROM " + table + where + orderBy(sort) + " LIMIT ? OFFSET ?",
                mapper, paged.toArray());
        return new Listings.Page<>(items, count(connection, "SELECT COUNT(*) AS n FROM " + table + where, params.toArray()));
    }

}
