package org.springframework.data.mongodb.core;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.util.StringUtils;
import com.alibaba.fastjson.JSON;
import com.google.common.base.CaseFormat;
import org.bson.Document;
import org.springframework.context.ApplicationContext;
import org.springframework.data.mongodb.annotation.ToMySQL;
import org.springframework.data.mongodb.annotation.ToMySQLTableColumn;
import org.springframework.data.mongodb.core.query.Query;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.springframework.data.mongodb.core.query.SerializationUtils.serializeToJsonSafely;

public class ToMySQLUtil {


    // 构造属性与Field的对应关系
    public static <T> FieldMapObject buildFieldMap(Class<T> entityClass) {

        FieldMapObject fieldMapObject = new FieldMapObject();

        Map<String/*属性名*/, Field> fieldMap = fieldMapObject.getFieldMap();
        Map<String/*字段名*/, Field> columnMap = fieldMapObject.getColumnMap();
        Map<String/*属性名*/, String/*字段名*/> fieldColumnMap = fieldMapObject.getFieldColumnMap();
        Map<String/*字段名*/, String/*属性名*/> columnFieldMap = fieldMapObject.getColumnFieldMap();

        Field[] fields = entityClass.getDeclaredFields();
        for (Field field : fields) {
            // 属性名
            // 例如 fieldName = createTime
            String fieldName = field.getName();
            fieldMap.put(fieldName, field);

            // 字段名
            String columnName = null;
            ToMySQLTableColumn fieldAnnotation = field.getAnnotation(ToMySQLTableColumn.class);
            if (fieldAnnotation != null && !isEmpty(fieldAnnotation.value())) {
                // 字段名称 例如 create_time
                columnName = fieldAnnotation.value();
                columnName = columnName.replaceAll("`", "");
                columnMap.put(columnName, field);
            }

            if (isEmpty(columnName)) {
                // 例如 将 createTime 转成 create_time
                columnName = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, fieldName);
                columnMap.put(columnName, field);
            }

            fieldColumnMap.put(fieldName, columnName);
            columnFieldMap.put(columnName, fieldName);
        }


        return fieldMapObject;
    }

    // 生成查询SQL语句
    public static <T> String createQuerySQL(String collectionName, Query query, Class<T> entityClass, FieldMapObject fieldMapObject) {

        StringBuilder buf = new StringBuilder();
        buf.append("SELECT * FROM ");

        // 表名
        String table = findTableName(collectionName, entityClass);
        buf.append(table);
        buf.append(" WHERE 1=1 ");


        Document queryObject = query.getQueryObject();
        System.out.println(serializeToJsonSafely(queryObject));

        // #1
        String andCondition = andCondition(queryObject, entityClass, fieldMapObject, "", "");
        buf.append(andCondition);

        if (query.isSorted()) {
            Document sortObject = query.getSortObject();
            System.out.println(serializeToJsonSafely(sortObject));

            // #2
            String orderByCondition = orderByCondition(sortObject, entityClass, fieldMapObject);
            buf.append(" ORDER BY " + orderByCondition);
        }

        // #3
        long skip = query.getSkip();
        int limit = query.getLimit();
        if (limit > 0) {
            buf.append(" LIMIT " + skip + "," + limit);
        } else {
            buf.append(" LIMIT " + skip);
        }

        return buf.toString();

    }

    // 表名
    // 查找顺序: 实体类上的ToMySQL注解 -> 集合名称
    private static <T> String findTableName(String collectionName, Class<T> entityClass) {

        String tableName = "`" + collectionName + "`";

        ToMySQL entityClassAnnotation = entityClass.getAnnotation(ToMySQL.class);
        if (entityClassAnnotation != null) {
            // 表
            String table = entityClassAnnotation.table();
            if (!isEmpty(table)) {
                table = table.replaceAll("`", "");
                // `t_order`
                tableName = "`" + table + "`";
            }
            // 库
            String database = entityClassAnnotation.database();
            if (!isEmpty(database)) {
                database = database.replaceAll("`", "");
                // `database`.`t_order`
                tableName = "`" + database + "`." + tableName;
            }
        }

        return tableName;
    }

    // 数据库列名
    private static <T> String findColumnName(String fieldName, Class<T> entityClass, FieldMapObject fieldMapObject) {
        Map<String, String> fieldColumnMap = fieldMapObject.getFieldColumnMap();
        String columnName = fieldColumnMap.get(fieldName);

        if (isEmpty(columnName)) {
            columnName = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, fieldName);
            if (!columnName.contains("$")) {
                fieldColumnMap.put(fieldName, columnName);
            }
        }

        return columnName;
    }

    // ORDER BY ...
    private static <T> String orderByCondition(Document document, Class<T> entityClass, FieldMapObject fieldMap) {
        StringBuilder buf = new StringBuilder();
        for (Map.Entry<String, Object> pair : document.entrySet()) {
            String fieldNameKey = pair.getKey();

            // 例如 fieldNameKey = 'areaCode,createTime'
            String[] fieldNameArr = fieldNameKey.split(",");
            StringBuilder orderBuf = new StringBuilder();

            for (int i = 0; i < fieldNameArr.length; i++) {
                String fieldName = fieldNameArr[i];
                orderBuf.append(findColumnName(fieldName, entityClass, fieldMap));
                if (i < (fieldNameArr.length - 1)) {
                    orderBuf.append(",");
                }
            }

            Integer fieldValue = (Integer) pair.getValue();

            buf.append(" " + orderBuf + (fieldValue < 0 ? " DESC ," : " ASC ,"));
        }

        return buf.substring(0, buf.toString().length() - 1);
    }

    // AND ... AND .. IN (...) AND .. >= . AND ...
    private static <T> String andCondition(Document document, Class<T> entityClass, FieldMapObject fieldMapObject, String prefixColumnName, String prefixAnd) {
        StringBuilder buf = new StringBuilder();

        int size = document.size();
        int i = 0;
        for (Map.Entry<String, Object> pair : document.entrySet()) {

            // 循环过程第一次不会添加 prefixAnd
            // 该情况常用在 create_time <= '2024-05-23' AND create_time > '2021-07-24'
            if (i > 0) {
                // 例如 prefixAnd = 'AND create_time'
                buf.append(prefixAnd);
            }
            i = i + 1;


            String fieldNameKey = pair.getKey();
            Object fieldValue = pair.getValue();
            String t;

            String columnName = findColumnName(fieldNameKey, entityClass, fieldMapObject);

            // 处理Key
            if (fieldNameKey.startsWith("$")) {
                if (fieldNameKey.equals("$in")) {
                    t = " IN ";
                    buf.append(t);
                } else if (fieldNameKey.equals("$nin")) {
                    t = " NOT IN ";
                    buf.append(t);
                } else if (fieldNameKey.equals("$gte")) {
                    t = " >= ";
                    buf.append(t);
                } else if (fieldNameKey.equals("$gt")) {
                    t = " > ";
                    buf.append(t);
                } else if (fieldNameKey.equals("$lte")) {
                    t = " <= ";
                    buf.append(t);
                } else if (fieldNameKey.equals("$lt")) {
                    t = " < ";
                    buf.append(t);
                } else if (fieldNameKey.equals("$ne")) {
                    if (fieldValue == null) {
                        t = " IS NOT NULL )";
                        buf.append(t);
                        continue;
                    } else {
                        t = " != ";
                        buf.append(t);
                    }
                } else if (fieldNameKey.equals("$exists")) {
                    // 暂时不支持
                    continue;
                    /*
                    if ((Boolean) fieldValue) { // 存在
                        t = " IS NOT NULL )";
                        buf.append(t);
                        continue;
                    } else { // 不存在
                        t = " IS NULL )";
                        buf.append(t);
                        continue;
                    }
                    */
                } else {
                    t = " " + columnName;
                    buf.append(t);
                }
            } else {
                if (fieldValue == null) {
                    t = " AND ( " + columnName + " IS NULL OR " + columnName + " = '' )";
                    buf.append(t);
                    continue;
                } else {
                    t = " AND ( " + columnName;
                    buf.append(t);
                }
            }


            // 处理value
            if (fieldValue instanceof String) {                     // 字符类型
                if (fieldNameKey.startsWith("$")) {
                    buf.append(" '" + fieldValue + "' ) ");
                } else {
                    if (isEmpty((String) fieldValue)) {
                        buf.append(" = '" + fieldValue + "' OR " + (isEmpty(prefixColumnName) ? columnName : prefixColumnName) + " IS NULL ) ");
                    } else {
                        buf.append(" = '" + fieldValue + "' ) ");
                    }
                }
            } else if (fieldValue instanceof Integer) {             // 数值类型
                if (fieldNameKey.startsWith("$")) {
                    buf.append(" " + fieldValue + " )");
                } else {
                    buf.append(" = " + fieldValue + " )");
                }
            } else if (fieldValue instanceof List) {                // 集合
                String _fieldValue = JSON.toJSONString(fieldValue);
                List<Object> vList = JSON.parseArray(_fieldValue, Object.class);

                String inCondition = inCondition(vList);
                buf.append(inCondition);
            } else if (fieldValue instanceof Pattern) {
                Pattern pattern = (Pattern) fieldValue;
                String regexp = pattern.pattern();
                // 如果已经指定了正则表达式则直接使用它
                if (regexp.contains("*") || regexp.contains("?") || regexp.contains(".")) {
                    buf.append(" REGEXP '" + pattern.pattern() + "' )");
                } else {
                    buf.append(" REGEXP '.*" + pattern.pattern() + ".*' )");
                }
            } else if (fieldValue instanceof Document) {
                if (((Document) fieldValue).size() > 1) {
                    // 递归
                    String v = andCondition((Document) fieldValue, entityClass, fieldMapObject, columnName, t);
                    buf.append(" " + v);
                } else {
                    // 递归
                    String v = andCondition((Document) fieldValue, entityClass, fieldMapObject, columnName, "");
                    buf.append(" " + v);
                }
            }


        }
        return buf.toString();
    }

    /**
     * 把集合转成小括号格式
     * 例如 ["A","B","C"] 转成 ("A","B","C”)
     */
    private static String inCondition(List<Object> vList) {

        StringBuilder inBuf = new StringBuilder();
        inBuf.append(" (");
        for (int i = 0; i < vList.size(); i++) {
            Object v = vList.get(i);
            if (v instanceof String) {
                inBuf.append("'" + v + "'");
            } else {
                inBuf.append(v);
            }
            if (i < (vList.size() - 1)) {
                inBuf.append(",");
            }
        }
        inBuf.append(") ) ");

        return inBuf.toString();
    }



    // 执行SQL
    public static <T> List<T> executeQuery(ApplicationContext applicationContext, Class<T> entityClass, FieldMapObject fieldMapObject, String sql) {
        Connection connection = null;
        List<T> dList = new ArrayList<>();
        try {
            // 1.获取数据库连接
            connection = getConnection(applicationContext);

            PreparedStatement statement = connection.prepareStatement(sql);
            // 2.执行查询
            ResultSet rs = statement.executeQuery();

            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();

            // 3.封装结果
            while (rs.next()) {
                T object = entityClass.newInstance();

                for (int i = 1; i <= columnCount; i++) {
                    String columnName = metaData.getColumnName(i);
                    Object columnValue = rs.getObject(i);

                    Map<String, Field> columnMap = fieldMapObject.getColumnMap();
                    Field field = columnMap.get(columnName);
                    if (field != null) {
                        field.setAccessible(true);

                        Class<?> type = field.getType();
                        if (type == String.class && columnValue instanceof Number) {
                            field.set(object, columnValue.toString());
                        } else {
                            field.set(object, columnValue);
                        }
                    }
                }
                dList.add(object);
            }

            return dList;
        } catch (Exception ignored) {

        } finally {
            if (connection != null) {
                try {
                    // 释放连接
                    connection.close();
                } catch (Exception ignored) {}
            }
        }

        return dList;
    }

    // 获取数据库连接
    private static Connection getConnection(ApplicationContext applicationContext) {
        Connection connection = null;
        Object dataSourceObject = applicationContext.getBean("dataSource");
        if (dataSourceObject instanceof DruidDataSource) {
            DruidDataSource dataSource = (DruidDataSource) dataSourceObject;
            try {
                connection = dataSource.getConnection();
                return connection;
            } catch (Exception ignored) {

            }
        }
        return connection;
    }

    private static boolean isEmpty(CharSequence value) {
        return value == null || value.length() == 0;
    }

    public static class FieldMapObject {
        private Map<String/*属性名*/, Field> fieldMap = new HashMap<>();
        private Map<String/*字段名*/, Field> columnMap = new HashMap<>();
        private Map<String/*属性名*/, String/*字段名*/> fieldColumnMap = new HashMap<>();
        private Map<String/*字段名*/, String/*属性名*/> columnFieldMap = new HashMap<>();

        public Map<String, Field> getFieldMap() {
            return fieldMap;
        }

        public Map<String, Field> getColumnMap() {
            return columnMap;
        }

        public Map<String, String> getFieldColumnMap() {
            return fieldColumnMap;
        }

        public Map<String, String> getColumnFieldMap() {
            return columnFieldMap;
        }

    }

}
