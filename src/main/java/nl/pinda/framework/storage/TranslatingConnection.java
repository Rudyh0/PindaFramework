package nl.pinda.framework.storage;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;

/**
 * Een verbinding met MySQL die de SQLite-SQL van de modules onderweg vertaalt
 * (zie {@link MysqlSql}). Modules merken er niets van.
 */
final class TranslatingConnection {

    private TranslatingConnection() {
    }

    static Connection wrap(Connection raw) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                new ConnectionHandler(raw));
    }

    /** De echte verbinding achter de vertaling. */
    static Connection unwrap(Connection connection) {
        if (Proxy.isProxyClass(connection.getClass()) && Proxy.getInvocationHandler(connection) instanceof ConnectionHandler handler) {
            return handler.raw;
        }
        return connection;
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private record ConnectionHandler(Connection raw) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ((name.equals("prepareStatement") || name.equals("prepareCall") || name.equals("nativeSQL"))
                    && args != null && args.length > 0 && args[0] instanceof String sql) {
                args[0] = MysqlSql.statement(sql);
            }
            Object result = TranslatingConnection.invoke(raw, method, args);
            if (name.equals("createStatement") && result instanceof Statement statement) {
                return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class},
                        new StatementHandler(statement, (Connection) proxy));
            }
            return result;
        }
    }

    private record StatementHandler(Statement raw, Connection connection) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ((name.startsWith("execute") || name.equals("addBatch")) && args != null && args.length > 0
                    && args[0] instanceof String sql) {
                args[0] = MysqlSql.statement(sql);
            }
            if (name.equals("getConnection")) {
                return connection;
            }
            return TranslatingConnection.invoke(raw, method, args);
        }
    }
}
