/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.sql.dml;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;

import org.lealone.common.exceptions.DbException;
import org.lealone.common.util.IOUtils;
import org.lealone.common.util.MathUtils;
import org.lealone.common.util.StatementBuilder;
import org.lealone.common.util.StringUtils;
import org.lealone.common.util.Utils;
import org.lealone.db.Comment;
import org.lealone.db.Constants;
import org.lealone.db.Database;
import org.lealone.db.DbObject;
import org.lealone.db.DbObjectType;
import org.lealone.db.SysProperties;
import org.lealone.db.api.ErrorCode;
import org.lealone.db.auth.Right;
import org.lealone.db.auth.Role;
import org.lealone.db.auth.User;
import org.lealone.db.constraint.Constraint;
import org.lealone.db.index.Cursor;
import org.lealone.db.index.Index;
import org.lealone.db.result.LocalResult;
import org.lealone.db.result.Result;
import org.lealone.db.result.Row;
import org.lealone.db.schema.Constant;
import org.lealone.db.schema.Schema;
import org.lealone.db.schema.SchemaObject;
import org.lealone.db.schema.Sequence;
import org.lealone.db.schema.TriggerObject;
import org.lealone.db.schema.UserAggregate;
import org.lealone.db.schema.UserDataType;
import org.lealone.db.session.ServerSession;
import org.lealone.db.table.Column;
import org.lealone.db.table.Table;
import org.lealone.db.table.TableType;
import org.lealone.db.value.Value;
import org.lealone.db.value.ValueString;
import org.lealone.sql.LealoneSQLParser;
import org.lealone.sql.SQLStatement;
import org.lealone.sql.expression.Expression;
import org.lealone.sql.expression.ExpressionColumn;
import org.lealone.sql.optimizer.Optimizer;
import org.lealone.sql.optimizer.PlanItem;

/**
 * This class represents the statement
 * SCRIPT
 */
// 此类用于生成SQL脚本，对应的SQL语句是SCRIPT，不是GEN SCRIPT
// 类名叫GenScript只是为了对应RunScript
public class GenScript extends ScriptBase {

    private Charset charset = Constants.UTF8;
    private Set<String> schemaNames;
    private Collection<Table> tables;
    private boolean passwords;
    /** true if we're generating the INSERT..VALUES statements for row values */
    private boolean data;
    private boolean settings;
    /** true if we're generating the DROP statements */
    private boolean drop;
    private boolean simple;
    private LocalResult result;
    private String lineSeparatorString;
    private byte[] lineSeparator;
    private byte[] buffer;
    private boolean tempLobTableCreated;
    private int nextLobId;
    private int lobBlockSize = Constants.IO_BUFFER_SIZE;

    public GenScript(ServerSession session) {
        super(session);
    }

    @Override
    public int getType() {
        return SQLStatement.SCRIPT;
    }

    public void setSimple(boolean simple) {
        this.simple = simple;
    }

    public void setCharset(Charset charset) {
        this.charset = charset;
    }

    @Override
    public boolean isQuery() {
        return true;
    }

    // TODO lock all tables for 'script' command

    public void setSchemaNames(Set<String> schemaNames) {
        this.schemaNames = schemaNames;
    }

    public void setTables(Collection<Table> tables) {
        this.tables = tables;
    }

    public void setData(boolean data) {
        this.data = data;
    }

    public void setPasswords(boolean passwords) {
        this.passwords = passwords;
    }

    public void setSettings(boolean settings) {
        this.settings = settings;
    }

    public void setLobBlockSize(long blockSize) {
        this.lobBlockSize = MathUtils.convertLongToInt(blockSize);
    }

    public void setDrop(boolean drop) {
        this.drop = drop;
    }

    @Override
    public Result getMetaData() {
        LocalResult r = createResult();
        r.done();
        return r;
    }

    private LocalResult createResult() {
        Expression[] expressions = { new ExpressionColumn(session.getDatabase(), new Column("SCRIPT", Value.STRING)) };
        return new LocalResult(session, expressions, 1);
    }

    @Override
    public Result query(int maxRows) {
        session.getUser().checkAdmin();
        reset();
        Database db = session.getDatabase();
        if (schemaNames != null) {
            for (String schemaName : schemaNames) {
                Schema schema = db.findSchema(session, schemaName);
                if (schema == null) {
                    throw DbException.get(ErrorCode.SCHEMA_NOT_FOUND_1, schemaName);
                }
            }
        }
        try {
            result = createResult();
            deleteStore();
            openOutput();
            if (out != null) {
                buffer = new byte[Constants.IO_BUFFER_SIZE];
            }
            if (settings) {
                for (Map.Entry<String, String> e : db.getParameters().entrySet()) {
                    String sql = "SET " + db.quoteIdentifier(e.getKey()) + " '" + e.getValue() + "'";
                    add(sql, false);
                }
            }
            if (out != null) {
                add("", true);
            }
            for (User user : db.getAllUsers()) {
                add(user.getCreateSQL(passwords), false);
            }
            for (Role role : db.getAllRoles()) {
                add(role.getCreateSQL(true), false);
            }
            for (Schema schema : db.getAllSchemas()) {
                if (excludeSchema(schema)) {
                    continue;
                }
                add(schema.getCreateSQL(), false);
            }
            for (SchemaObject obj : db.getAllSchemaObjects(DbObjectType.AGGREGATE)) {
                if (excludeSchema(obj.getSchema())) {
                    continue;
                }
                UserAggregate agg = (UserAggregate) obj;
                add(agg.getCreateSQL(), false);
            }
            for (SchemaObject obj : db.getAllSchemaObjects(DbObjectType.USER_DATATYPE)) {
                if (excludeSchema(obj.getSchema())) {
                    continue;
                }
                UserDataType dt = (UserDataType) obj;
                add(dt.getCreateSQL(), false);
            }
            for (SchemaObject obj : db.getAllSchemaObjects(DbObjectType.CONSTANT)) {
                if (excludeSchema(obj.getSchema())) {
                    continue;
                }
                Constant constant = (Constant) obj;
                add(constant.getCreateSQL(), false);
            }

            final ArrayList<Table> tables = db.getAllTablesAndViews(false);
            // sort by id, so that views are after tables and views on views
            // after the base views
            Collections.sort(tables, new Comparator<Table>() {
                @Override
                public int compare(Table t1, Table t2) {
                    return t1.getId() - t2.getId();
                }
            });

            // Generate the DROP XXX ... IF EXISTS
            for (Table table : tables) {
                if (excludeSchema(table.getSchema())) {
                    continue;
                }
                if (excludeTable(table)) {
                    continue;
                }
                if (table.isHidden()) {
                    continue;
                }
                table.lock(session, false);
                String sql = table.getCreateSQL();
                if (sql == null) {
                    // null for metadata tables
                    continue;
                }
                if (drop) {
                    add(table.getDropSQL(), false);
                }
            }
            for (SchemaObject obj : db.getAllSchemaObjects(DbObjectType.FUNCTION_ALIAS)) {
                if (excludeSchema(obj.getSchema())) {
                    continue;
                }
                if (drop) {
                    add(obj.getDropSQL(), false);
                }
                add(obj.getCreateSQL(), false);
            }
            for (SchemaObject obj : db.getAllSchemaObjects(DbObjectType.SEQUENCE)) {
                if (excludeSchema(obj.getSchema())) {
                    continue;
                }
                Sequence sequence = (Sequence) obj;
                if (drop) {
                    add(sequence.getDropSQL(), false);
                }
                add(sequence.getCreateSQL(), false);
            }

            // Generate CREATE TABLE and INSERT...VALUES
            int count = 0;
            for (Table table : tables) {
                if (excludeSchema(table.getSchema())) {
                    continue;
                }
                if (excludeTable(table)) {
                    continue;
                }
                if (table.isHidden()) {
                    continue;
                }
                table.lock(session, false);
                String createTableSql = table.getCreateSQL();
                if (createTableSql == null) {
                    // null for metadata tables
                    continue;
                }
                final TableType tableType = table.getTableType();
                add(createTableSql, false);
                final ArrayList<Constraint> constraints = table.getConstraints();
                if (constraints != null) {
                    for (Constraint constraint : constraints) {
                        if (Constraint.PRIMARY_KEY.equals(constraint.getConstraintType())) {
                            add(constraint.getCreateSQLWithoutIndexes(), false);
                        }
                    }
                }
                if (tableType == TableType.STANDARD_TABLE) {
                    if (table.canGetRowCount()) {
                        String rowcount = "-- " + table.getRowCountApproximation() + " +/- SELECT COUNT(*) FROM "
                                + table.getSQL();
                        add(rowcount, false);
                    }
                    if (data) {
                        count = generateInsertValues(count, table);
                    }
                }
                final ArrayList<Index> indexes = table.getIndexes();
                for (int j = 0; indexes != null && j < indexes.size(); j++) {
                    Index index = indexes.get(j);
                    if (!index.getIndexType().getBelongsToConstraint()) {
                        add(index.getCreateSQL(), false);
                    }
                }
            }
            if (tempLobTableCreated) {
                add("DROP TABLE IF EXISTS SYSTEM_LOB_STREAM", true);
                add("CALL SYSTEM_COMBINE_BLOB(-1)", true);
                add("DROP ALIAS IF EXISTS SYSTEM_COMBINE_CLOB", true);
                add("DROP ALIAS IF EXISTS SYSTEM_COMBINE_BLOB", true);
                tempLobTableCreated = false;
            }
            // Generate CREATE CONSTRAINT ...
            final ArrayList<SchemaObject> constraints = db.getAllSchemaObjects(DbObjectType.CONSTRAINT);
            Collections.sort(constraints, new Comparator<SchemaObject>() {
                @Override
                public int compare(SchemaObject c1, SchemaObject c2) {
                    return ((Constraint) c1).compareTo((Constraint) c2);
                }
            });
            for (SchemaObject obj : constraints) {
                if (excludeSchema(obj.getSchema())) {
                    continue;
                }
                Constraint constraint = (Constraint) obj;
                if (excludeTable(constraint.getTable())) {
                    continue;
                }
                if (constraint.getTable().isHidden()) {
                    continue;
                }
                if (!Constraint.PRIMARY_KEY.equals(constraint.getConstraintType())) {
                    add(constraint.getCreateSQLWithoutIndexes(), false);
                }
            }
            // Generate CREATE TRIGGER ...
            for (SchemaObject obj : db.getAllSchemaObjects(DbObjectType.TRIGGER)) {
                if (excludeSchema(obj.getSchema())) {
                    continue;
                }
                TriggerObject trigger = (TriggerObject) obj;
                if (excludeTable(trigger.getTable())) {
                    continue;
                }
                add(trigger.getCreateSQL(), false);
            }
            // Generate GRANT ...
            for (Right right : db.getAllRights()) {
                DbObject object = right.getGrantedObject();
                if (object != null) {
                    if (object instanceof Schema) {
                        if (excludeSchema((Schema) object)) {
                            continue;
                        }
                    } else if (object instanceof Table) {
                        Table table = (Table) object;
                        if (excludeSchema(table.getSchema())) {
                            continue;
                        }
                        if (excludeTable(table)) {
                            continue;
                        }
                    }
                }
                add(right.getCreateSQL(), false);
            }
            // Generate COMMENT ON ...
            for (Comment comment : db.getAllComments()) {
                add(comment.getCreateSQL(), false);
            }
            if (out != null) {
                out.close();
            }
        } catch (IOException e) {
            throw DbException.convertIOException(e, getFileName());
        } finally {
            closeIO();
        }
        result.done();
        LocalResult r = result;
        reset();
        return r;
    }

    private int generateInsertValues(int count, Table table) throws IOException {
        PlanItem plan = Optimizer.getBestPlanItem(session, null, table, null);
        Index index = plan.getIndex();
        Cursor cursor = index.find(session, null, null);
        Column[] columns = table.getColumns();
        StatementBuilder buff = new StatementBuilder("INSERT INTO ");
        buff.append(table.getSQL()).append('(');
        for (Column col : columns) {
            buff.appendExceptFirst(", ");
            buff.append(LealoneSQLParser.quoteIdentifier(col.getName()));
        }
        buff.append(") VALUES");
        if (!simple) {
            buff.append('\n');
        }
        buff.append('(');
        String ins = buff.toString();
        buff = null;
        while (cursor.next()) {
            Row row = cursor.get();
            if (buff == null) {
                buff = new StatementBuilder(ins);
            } else {
                buff.append(",\n(");
            }
            for (int j = 0; j < row.getColumnCount(); j++) {
                if (j > 0) {
                    buff.append(", ");
                }
                Value v = row.getValue(j);
                if (v.getPrecision() > lobBlockSize) {
                    int id;
                    if (v.getType() == Value.CLOB) {
                        id = writeLobStream(v);
                        buff.append("SYSTEM_COMBINE_CLOB(" + id + ")");
                    } else if (v.getType() == Value.BLOB) {
                        id = writeLobStream(v);
                        buff.append("SYSTEM_COMBINE_BLOB(" + id + ")");
                    } else {
                        buff.append(v.getSQL());
                    }
                } else {
                    buff.append(v.getSQL());
                }
            }
            buff.append(')');
            count++;
            if ((count & 127) == 0) {
                checkCanceled();
            }
            if (simple || buff.length() > Constants.IO_BUFFER_SIZE) {
                add(buff.toString(), true);
                buff = null;
            }
        }
        if (buff != null) {
            add(buff.toString(), true);
        }
        return count;
    }

    private int writeLobStream(Value v) throws IOException {
        if (!tempLobTableCreated) {
            add("CREATE TABLE IF NOT EXISTS SYSTEM_LOB_STREAM"
                    + "(ID INT NOT NULL, PART INT NOT NULL, CDATA VARCHAR, BDATA BINARY)", true);
            add("CREATE PRIMARY KEY SYSTEM_LOB_STREAM_PRIMARY_KEY " + "ON SYSTEM_LOB_STREAM(ID, PART)", true);
            add("CREATE ALIAS IF NOT EXISTS " + "SYSTEM_COMBINE_CLOB FOR \"" + this.getClass().getName()
                    + ".combineClob\"", true);
            add("CREATE ALIAS IF NOT EXISTS " + "SYSTEM_COMBINE_BLOB FOR \"" + this.getClass().getName()
                    + ".combineBlob\"", true);
            tempLobTableCreated = true;
        }
        int id = nextLobId++;
        switch (v.getType()) {
        case Value.BLOB: {
            byte[] bytes = new byte[lobBlockSize];
            InputStream input = v.getInputStream();
            try {
                for (int i = 0;; i++) {
                    StringBuilder buff = new StringBuilder(lobBlockSize * 2);
                    buff.append("INSERT INTO SYSTEM_LOB_STREAM VALUES(" + id + ", " + i + ", NULL, '");
                    int len = IOUtils.readFully(input, bytes, lobBlockSize);
                    if (len <= 0) {
                        break;
                    }
                    buff.append(StringUtils.convertBytesToHex(bytes, len)).append("')");
                    String sql = buff.toString();
                    add(sql, true);
                }
            } finally {
                IOUtils.closeSilently(input);
            }
            break;
        }
        case Value.CLOB: {
            char[] chars = new char[lobBlockSize];
            Reader reader = v.getReader();
            try {
                for (int i = 0;; i++) {
                    StringBuilder buff = new StringBuilder(lobBlockSize * 2);
                    buff.append("INSERT INTO SYSTEM_LOB_STREAM VALUES(" + id + ", " + i + ", ");
                    int len = IOUtils.readFully(reader, chars, lobBlockSize);
                    if (len < 0) {
                        break;
                    }
                    buff.append(StringUtils.quoteStringSQL(new String(chars, 0, len))).append(", NULL)");
                    String sql = buff.toString();
                    add(sql, true);
                }
            } finally {
                IOUtils.closeSilently(reader);
            }
            break;
        }
        default:
            DbException.throwInternalError("type:" + v.getType());
        }
        return id;
    }

    /**
     * Combine a BLOB.
     * This method is called from the script.
     * When calling with id -1, the file is deleted.
     *
     * @param conn a connection
     * @param id the lob id
     * @return a stream for the combined data
     */
    public static InputStream combineBlob(Connection conn, int id) throws SQLException {
        if (id < 0) {
            return null;
        }
        final ResultSet rs = getLobStream(conn, "BDATA", id);
        return new InputStream() {
            private InputStream current;
            private boolean closed;

            @Override
            public int read() throws IOException {
                while (true) {
                    try {
                        if (current == null) {
                            if (closed) {
                                return -1;
                            }
                            if (!rs.next()) {
                                close();
                                return -1;
                            }
                            current = rs.getBinaryStream(1);
                            current = new BufferedInputStream(current);
                        }
                        int x = current.read();
                        if (x >= 0) {
                            return x;
                        }
                        current = null;
                    } catch (SQLException e) {
                        throw DbException.convertToIOException(e);
                    }
                }
            }

            @Override
            public void close() throws IOException {
                if (closed) {
                    return;
                }
                closed = true;
                try {
                    rs.close();
                } catch (SQLException e) {
                    throw DbException.convertToIOException(e);
                }
            }
        };
    }

    /**
     * Combine a CLOB.
     * This method is called from the script.
     *
     * @param conn a connection
     * @param id the lob id
     * @return a reader for the combined data
     */
    public static Reader combineClob(Connection conn, int id) throws SQLException {
        if (id < 0) {
            return null;
        }
        final ResultSet rs = getLobStream(conn, "CDATA", id);
        return new Reader() {
            private Reader current;
            private boolean closed;

            @Override
            public int read() throws IOException {
                while (true) {
                    try {
                        if (current == null) {
                            if (closed) {
                                return -1;
                            }
                            if (!rs.next()) {
                                close();
                                return -1;
                            }
                            current = rs.getCharacterStream(1);
                            current = new BufferedReader(current);
                        }
                        int x = current.read();
                        if (x >= 0) {
                            return x;
                        }
                        current = null;
                    } catch (SQLException e) {
                        throw DbException.convertToIOException(e);
                    }
                }
            }

            @Override
            public void close() throws IOException {
                if (closed) {
                    return;
                }
                closed = true;
                try {
                    rs.close();
                } catch (SQLException e) {
                    throw DbException.convertToIOException(e);
                }
            }

            @Override
            public int read(char[] buffer, int off, int len) throws IOException {
                if (len == 0) {
                    return 0;
                }
                int c = read();
                if (c == -1) {
                    return -1;
                }
                buffer[off] = (char) c;
                int i = 1;
                for (; i < len; i++) {
                    c = read();
                    if (c == -1) {
                        break;
                    }
                    buffer[off + i] = (char) c;
                }
                return i;
            }
        };
    }

    private static ResultSet getLobStream(Connection conn, String column, int id) throws SQLException {
        PreparedStatement prep = conn
                .prepareStatement("SELECT " + column + " FROM SYSTEM_LOB_STREAM WHERE ID=? ORDER BY PART");
        prep.setInt(1, id);
        return prep.executeQuery();
    }

    private void reset() {
        result = null;
        buffer = null;
        lineSeparatorString = SysProperties.LINE_SEPARATOR;
        lineSeparator = lineSeparatorString.getBytes(charset);
    }

    private boolean excludeSchema(Schema schema) {
        if (schemaNames != null && !schemaNames.contains(schema.getName())) {
            return true;
        }
        if (tables != null) {
            // if filtering on specific tables, only include those schemas
            for (Table table : schema.getAllTablesAndViews()) {
                if (tables.contains(table)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private boolean excludeTable(Table table) {
        return tables != null && !tables.contains(table);
    }

    private void add(String s, boolean insert) throws IOException {
        if (s == null) {
            return;
        }
        if (lineSeparator.length > 1 || lineSeparator[0] != '\n') {
            s = StringUtils.replaceAll(s, "\n", lineSeparatorString);
        }
        s += ";";
        if (out != null) {
            byte[] buff = s.getBytes(charset);
            int len = MathUtils.roundUpInt(buff.length + lineSeparator.length, Constants.FILE_BLOCK_SIZE);
            buffer = Utils.copy(buff, buffer);

            if (len > buffer.length) {
                buffer = new byte[len];
            }
            System.arraycopy(buff, 0, buffer, 0, buff.length);
            for (int i = buff.length; i < len - lineSeparator.length; i++) {
                buffer[i] = ' ';
            }
            for (int j = 0, i = len - lineSeparator.length; i < len; i++, j++) {
                buffer[i] = lineSeparator[j];
            }
            out.write(buffer, 0, len);
            if (!insert) {
                Value[] row = { ValueString.get(s) };
                result.addRow(row);
            }
        } else {
            Value[] row = { ValueString.get(s) };
            result.addRow(row);
        }
    }

}
