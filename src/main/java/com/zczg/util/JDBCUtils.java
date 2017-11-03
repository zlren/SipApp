package com.zczg.util;


import org.apache.log4j.Logger;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.*;
import java.util.*;


public class JDBCUtils {

    private static String URL = "";
    private static String USER_NAME = "";
    private static String PASSWORD = "";

    private static Logger logger = Logger.getLogger(JDBCUtils.class);

    static {
        try {

            Properties jdbcProperties = new Properties();
            InputStream resourceAsStream = JDBCUtils.class.getClassLoader().getResourceAsStream("jdbc.properties");
            jdbcProperties.load(resourceAsStream);

            String DRIVER = jdbcProperties.getProperty("jdbc.driver");
            URL = jdbcProperties.getProperty("jdbc.url");
            USER_NAME = jdbcProperties.getProperty("jdbc.username");
            PASSWORD = jdbcProperties.getProperty("jdbc.password");

            Class.forName(DRIVER);

            logger.info("Successfully load database properties");
        } catch (Exception ignored) {
        }
    }

    /**
     * Get connection
     *
     * @return
     */
    public static Connection getconnnection() {
        Connection con = null;
        try {
            con = DriverManager.getConnection(URL, USER_NAME, PASSWORD);
            logger.info("Successfully connect to the database: " + URL);
        } catch (SQLException e) {
        }
        return con;
    }

    /**
     * Close connection
     *
     * @param rs
     * @param st
     * @param con
     */
    public static void close(ResultSet rs, Statement st, Connection con) {
        try {
            try {
                if (rs != null) {
                    rs.close();
                }
            } finally {
                try {
                    if (st != null) {
                        st.close();
                    }
                } finally {
                    if (con != null)
                        con.close();
                }
            }
        } catch (SQLException e) {
            // e.printStackTrace();
//			logger.error(e.getCause());
        }
    }

    /**
     * Close connection
     *
     * @param rs
     */
    public static void close(ResultSet rs) {
        Statement st = null;
        Connection con = null;
        try {
            try {
                if (rs != null) {
                    st = rs.getStatement();
                    rs.close();
                }
            } finally {
                try {
                    if (st != null) {
                        con = st.getConnection();
                        st.close();
                    }
                } finally {
                    if (con != null) {
                        con.close();
                    }
                }
            }
        } catch (SQLException e) {
            // e.printStackTrace();
//			logger.error(e.getCause());
        }
    }

    /**
     * Close connection
     *
     * @param st
     * @param con
     */
    public static void close(Statement st, Connection con) {
        try {
            try {
                if (st != null) {
                    st.close();
                }
            } finally {
                if (con != null)
                    con.close();
            }
        } catch (SQLException e) {
            // e.printStackTrace();
//			logger.error(e.getCause());
        }
    }

    /**
     * insert/update/delete
     *
     * @param sql
     * @param args
     * @return
     */
    public static int update(String sql, Object... args) {
        int result = 0;
        Connection con = getconnnection();
        PreparedStatement ps = null;
        try {
            ps = con.prepareStatement(sql);
            System.out.println(ps.toString());
            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject((i + 1), args[i]);
                }
            }
            System.out.println(ps.toString());
            result = ps.executeUpdate();
        } catch (SQLException e) {
            // e.printStackTrace();
//			logger.error(e.getCause());
        } finally {
            close(ps, con);
        }

        return result;
    }

    /**
     * Query a single record
     *
     * @param sql
     * @param args
     * @return Map<String,Object>
     */
    public static Map<String, Object> queryForMap(String sql, Object... args) {
        Map<String, Object> result = new HashMap<String, Object>();
        List<Map<String, Object>> list = queryForList(sql, args);
        if (list.size() > 0) {
            result = list.get(0);
        }
        return result;
    }

    /**
     * Query a single record
     *
     * @param sql
     * @param args
     * @return <T>
     */
    public static <T> T queryForObject(String sql, Class<T> clz, Object... args) {
        T result = null;
        List<T> list = queryForList(sql, clz, args);
        if (list.size() > 0) {
            result = list.get(0);
        }
        return result;
    }

    /**
     * Query a single record
     *
     * @param sql
     * @param args
     * @return List<Map<String,Object>>
     */
    public static List<Map<String, Object>> queryForList(String sql, Object... args) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        Connection con = null;
        ResultSet rs = null;
        PreparedStatement ps = null;
        try {
            con = getconnnection();
            ps = con.prepareStatement(sql);
            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject((i + 1), args[i]);
                }
            }
            rs = ps.executeQuery();
            ResultSetMetaData rsmd = rs.getMetaData();
            int columnCount = rsmd.getColumnCount();
            while (rs.next()) {
                Map<String, Object> map = new HashMap<String, Object>();
                for (int i = 1; i <= columnCount; i++) {
                    map.put(rsmd.getColumnLabel(i), rs.getObject(i));
                }
                result.add(map);
            }
        } catch (SQLException e) {
            // e.printStackTrace();
//			logger.error(e.getCause());
        } finally {
            close(rs, ps, con);
        }
        return result;
    }

    /**
     * Query a single record
     *
     * @param sql
     * @param args
     * @return List<T>
     */
    public static <T> List<T> queryForList(String sql, Class<T> clz, Object... args) {
        List<T> result = new ArrayList<T>();
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            con = getconnnection();
            ps = con.prepareStatement(sql);
            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject((i + 1), args[i]);
                }
            }
            rs = ps.executeQuery();
            ResultSetMetaData rsmd = rs.getMetaData();
            int columnCount = rsmd.getColumnCount();
            while (rs.next()) {
                T obj = clz.newInstance();
                for (int i = 1; i <= columnCount; i++) {
                    String columnName = rsmd.getColumnName(i);
                    String methodName = "set" + columnName.substring(0, 1).toUpperCase()
                            + columnName.substring(1, columnName.length());
                    Method method[] = clz.getMethods();
                    for (Method meth : method) {
                        if (methodName.equals(meth.getName())) {
                            meth.invoke(obj, rs.getObject(i));
                        }
                    }
                }
                result.add(obj);
            }
        } catch (InstantiationException e) {
            // e.printStackTrace();
//			logger.error(e.getCause());
        } catch (IllegalAccessException e) {
            // e.printStackTrace();
//			logger.error(e.getCause());
        } catch (SQLException e) {
            // e.printStackTrace();
//			logger.error(e.getCause());
        } catch (IllegalArgumentException e) {
            // e.printStackTrace();
//			logger.error(e.getCause());
        } catch (InvocationTargetException e) {
            // e.printStackTrace();
//			logger.error(e.getCause());
        } finally {
            close(rs, ps, con);
        }
        return result;
    }
}
