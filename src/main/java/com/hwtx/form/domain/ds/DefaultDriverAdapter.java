package com.hwtx.form.domain.ds;


import lombok.extern.slf4j.Slf4j;
import org.anyline.adapter.DataReader;
import org.anyline.adapter.DataWriter;
import org.anyline.adapter.KeyAdapter;
import org.anyline.adapter.init.ConvertAdapter;
import org.anyline.data.metadata.StandardColumnType;
import org.anyline.data.param.ConfigStore;
import org.anyline.data.run.RunValue;
import org.anyline.data.util.DataSourceUtil;
import org.anyline.entity.Compare;
import org.anyline.entity.DataRow;
import org.anyline.entity.DataSet;
import org.anyline.metadata.*;
import org.anyline.metadata.type.ColumnType;
import org.anyline.metadata.type.DatabaseType;
import org.anyline.proxy.EntityAdapterProxy;
import org.anyline.util.*;
import org.apache.commons.collections4.CollectionUtils;

import java.lang.reflect.Field;
import java.sql.ResultSet;
import java.util.*;
import java.util.stream.Collectors;


/**
 * SQL生成 子类主要实现与分页相关的SQL 以及delimiter
 */

@Slf4j
public abstract class DefaultDriverAdapter implements DriverAdapter {
    public String delimiterFr = "";
    public String delimiterTo = "";

    //根据名称定准数据类型
    protected Map<String, ColumnType> types = new Hashtable();

    public DefaultDriverAdapter() {
        //当前数据库支持的数据类型,子类根据情况覆盖
        for (StandardColumnType type : StandardColumnType.values()) {
            DatabaseType[] dbs = type.dbs();
            for (DatabaseType db : dbs) {
                if (db == this.type()) {
                    //column type支持当前db
                    types.put(type.getName(), type);
                    break;
                }
            }
        }
    }

    @Override
    public String getDelimiterFr() {
        return this.delimiterFr;
    }

    @Override
    public String getDelimiterTo() {
        return this.delimiterTo;
    }

    public void setDelimiter(String delimiter) {
        if (BasicUtil.isNotEmpty(delimiter)) {
            delimiter = delimiter.replaceAll("\\s", "");
            if (delimiter.length() == 1) {
                this.delimiterFr = delimiter;
                this.delimiterTo = delimiter;
            } else {
                this.delimiterFr = delimiter.substring(0, 1);
                this.delimiterTo = delimiter.substring(1, 2);
            }
        }
    }

    /**
     * insert [命令合成-子流程]<br/>
     * 设置主键值
     *
     * @param obj   obj
     * @param value value
     */
    protected void setPrimaryValue(Object obj, Object value) {
        if (null == obj) {
            return;
        }
        if (obj instanceof DataRow) {
            DataRow row = (DataRow) obj;
            row.put(row.getPrimaryKey(), value);
        } else {
            Column key = EntityAdapterProxy.primaryKey(obj.getClass());
            Field field = EntityAdapterProxy.field(obj.getClass(), key);
            BeanUtil.setFieldValue(obj, field, value);
        }
    }

    protected Boolean checkOverride(Object obj) {
        Boolean result = null;
        if (null != obj && obj instanceof DataRow) {
            result = ((DataRow) obj).getOverride();
        }
        return result;
    }

    protected Map<String, Object> checkPv(Object obj) {
        Map<String, Object> pvs = new HashMap<>();
        if (null != obj && obj instanceof DataRow) {
            DataRow row = (DataRow) obj;
            List<String> ks = row.getPrimaryKeys();
            for (String k : ks) {
                pvs.put(k, row.get(k));
            }
        }
        return pvs;
    }

    /**
     * 过滤掉表结构中不存在的列
     *
     * @param table   表
     * @param columns columns
     * @return List
     */
    public LinkedHashMap<String, Column> checkMetadata(DataRuntime runtime, String table, ConfigStore configs, LinkedHashMap<String, Column> columns) {
        if (!IS_AUTO_CHECK_METADATA(configs)) {
            return columns;
        }
        LinkedHashMap<String, Column> result = new LinkedHashMap<>();
        LinkedHashMap<String, Column> metadatas = columns(runtime, null, false, new Table(table), false);
        if (metadatas.size() > 0) {
            for (String key : columns.keySet()) {
                if (metadatas.containsKey(key)) {
                    result.put(key, metadatas.get(key));
                } else {
                    if (IS_LOG_SQL_WARN(configs)) {
                        log.warn("[{}][column:{}.{}][insert/update忽略当前列名]", LogUtil.format("列名检测不存在", 33), table, key);
                    }
                }
            }
        } else {
            if (IS_LOG_SQL_WARN(configs)) {
                log.warn("[{}][table:{}][忽略列名检测]", LogUtil.format("表结构检测失败(检查表名是否存在)", 33), table);
            }
        }
        if (IS_LOG_SQL_WARN(configs)) {
            log.info("[check column metadata][src:{}][result:{}]", columns.size(), result.size());
        }
        return result;
    }

    /**
     * schema[调用入口]
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param random  用来标记同一组命令
     * @param catalog catalog
     * @param name    名称统配符或正则
     * @return LinkedHashMap
     */
    @Override
    public LinkedHashMap<String, Schema> schemas(DataRuntime runtime, String random, Catalog catalog, String name) {
        if (null == random) {
            random = random(runtime);
        }
        LinkedHashMap<String, Schema> schemas = new LinkedHashMap<>();
        try {
            long fr = System.currentTimeMillis();
            // 根据系统表查询
            try {
                List<Run> runs = buildQuerySchemaRun(runtime, false, catalog, name);
                if (null != runs) {
                    int idx = 0;
                    for (Run run : runs) {
//                        DataSet set = select(runtime, random, true, null, new DefaultConfigStore().keyCase(KeyAdapter.KEY_CASE.PUT_UPPER), run).toUpperKey();
//                        schemas = schemas(runtime, idx++, true, schemas, set);
                    }
                }
            } catch (Exception e) {
                if (ConfigTable.IS_PRINT_EXCEPTION_STACK_TRACE) {
                    e.printStackTrace();
                } else if (ConfigTable.IS_LOG_SQL && log.isWarnEnabled()) {
                    log.warn("{}[schemas][{}][msg:{}]", random, LogUtil.format("根据系统表查询失败", 33), e.toString());
                }
            }
            if (ConfigTable.IS_LOG_SQL_TIME && log.isInfoEnabled()) {
                log.info("{}[schemas][result:{}][执行耗时:{}ms]", random, schemas.size(), System.currentTimeMillis() - fr);
            }
        } catch (Exception e) {
            if (ConfigTable.IS_PRINT_EXCEPTION_STACK_TRACE) {
                e.printStackTrace();
            } else {
                log.error("[schemas][result:fail][msg:{}]", e.toString());
            }
        }
        return schemas;
    }

    /**
     * schema[调用入口]
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param random  用来标记同一组命令
     * @param catalog catalog
     * @param name    名称统配符或正则
     * @return LinkedHashMap
     */
    @Override
    public List<Schema> schemas(DataRuntime runtime, String random, boolean greedy, Catalog catalog, String name) {
        if (null == random) {
            random = random(runtime);
        }
        List<Schema> schemas = new ArrayList<>();
        try {
            long fr = System.currentTimeMillis();
            // 根据系统表查询
            try {
                List<Run> runs = buildQuerySchemaRun(runtime, greedy, catalog, name);
                if (null != runs) {
                    int idx = 0;
                    for (Run run : runs) {
//                        DataSet set = select(runtime, random, true, null, new DefaultConfigStore().keyCase(KeyAdapter.KEY_CASE.PUT_UPPER), run).toUpperKey();
//                        schemas = schemas(runtime, idx++, true, schemas, set);
                    }
                }
            } catch (Exception e) {
                if (ConfigTable.IS_PRINT_EXCEPTION_STACK_TRACE) {
                    e.printStackTrace();
                } else if (ConfigTable.IS_LOG_SQL && log.isWarnEnabled()) {
                    log.warn("{}[schemas][{}][msg:{}]", random, LogUtil.format("根据系统表查询失败", 33), e.toString());
                }
            }
            if (ConfigTable.IS_LOG_SQL_TIME && log.isInfoEnabled()) {
                log.info("{}[schemas][result:{}][执行耗时:{}ms]", random, schemas.size(), System.currentTimeMillis() - fr);
            }
        } catch (Exception e) {
            if (ConfigTable.IS_PRINT_EXCEPTION_STACK_TRACE) {
                e.printStackTrace();
            } else {
                log.error("[schemas][result:fail][msg:{}]", e.toString());
            }
        }
        return schemas;
    }

    /**
     * catalog[命令合成]<br/>
     * 查询所有数据库
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param name    名称统配符或正则
     * @param greedy  贪婪模式 true:查询权限范围内尽可能多的数据
     * @return sqls
     * @throws Exception 异常
     */
    public List<Run> buildQuerySchemaRun(DataRuntime runtime, boolean greedy, Catalog catalog, String name) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 List<Run> buildQuerySchemaRun(DataRuntime runtime, boolean greedy, Catalog catalog, String name)", 37));
        }
        return new ArrayList<>();
    }

    /**
     * schema[结果集封装]<br/>
     * 根据查询结果集构造 Schema
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param index   第几条SQL 对照 buildQueryDatabaseRun 返回顺序
     * @param create  上一步没有查到的,这一步是否需要新创建
     * @param schemas 上一步查询结果
     * @param set     查询结果集
     * @return databases
     * @throws Exception 异常
     */
    public LinkedHashMap<String, Schema> schemas(DataRuntime runtime, int index, boolean create, LinkedHashMap<String, Schema> schemas, DataSet set) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 LinkedHashMap<String, Schema> schemas(DataRuntime runtime, int index, boolean create, LinkedHashMap<String, Schema> schemas, DataSet set)", 37));
        }
        return new LinkedHashMap<>();
    }

    public List<Schema> schemas(DataRuntime runtime, int index, boolean create, List<Schema> schemas, DataSet set) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 List<Schema> schemas(DataRuntime runtime, int index, boolean create, List<Schema> schemas, DataSet set)", 37));
        }
        return new ArrayList<>();
    }

    /**
     * schema[结果集封装]<br/>
     * 根据驱动内置接口补充 Schema
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param create  上一步没有查到的,这一步是否需要新创建
     * @param schemas 上一步查询结果
     * @return databases
     * @throws Exception 异常
     */
    public LinkedHashMap<String, Schema> schemas(DataRuntime runtime, boolean create, LinkedHashMap<String, Schema> schemas) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 LinkedHashMap<String, Schema> schemas(DataRuntime runtime, boolean create, LinkedHashMap<String, Schema> schemas)", 37));
        }
        return new LinkedHashMap<>();
    }

    /**
     * schema[结果集封装]<br/>
     * 根据驱动内置接口补充 Schema
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param create  上一步没有查到的,这一步是否需要新创建
     * @param schemas 上一步查询结果
     * @return databases
     * @throws Exception 异常
     */
    public List<Schema> schemas(DataRuntime runtime, boolean create, List<Schema> schemas) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 List<Schema> schemas(DataRuntime runtime, boolean create, List<Schema> schemas)", 37));
        }
        return new ArrayList<>();
    }

    public Schema schema(DataRuntime runtime, int index, boolean create, DataSet set) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 Schema schema(DataRuntime runtime, int index, boolean create, DataSet set)", 37));
        }
        return null;
    }

    /**
     * table[命令合成]<br/>
     * 查询表,不是查表中的数据
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param catalog catalog
     * @param schema  schema
     * @param pattern 名称统配符或正则
     * @param types   "TABLE", "VIEW", "SYSTEM TABLE", "GLOBAL TEMPORARY", "LOCAL TEMPORARY", "ALIAS", "SYNONYM".
     * @return String
     */
    @Override
    public List<Run> buildQueryTablesRun(DataRuntime runtime, Catalog catalog, Schema schema, String pattern, String types) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 List<Run> buildQueryTableRun(DataRuntime runtime, Catalog catalog, Schema schema, String pattern, String types)", 37));
        }
        return new ArrayList<>();
    }


    /**
     * table[命令合成]<br/>
     * 查询表备注
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param catalog catalog
     * @param schema  schema
     * @param pattern 名称统配符或正则
     * @param types   types "TABLE", "VIEW", "SYSTEM TABLE", "GLOBAL TEMPORARY", "LOCAL TEMPORARY", "ALIAS", "SYNONYM".
     * @return String
     */
    public List<Run> buildQueryTableCommentRun(DataRuntime runtime, Catalog catalog, Schema schema, String pattern, String types) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 List<Run> buildQueryTableCommentRun(DataRuntime runtime, Catalog catalog, Schema schema, String pattern, String types)", 37));
        }
        return new ArrayList<>();
    }

    /**
     * table[结果集封装]<br/>
     * 表备注
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param index   第几条SQL 对照buildQueryTableRun返回顺序
     * @param create  上一步没有查到的,这一步是否需要新创建
     * @param catalog catalog
     * @param schema  schema
     * @param tables  上一步查询结果
     * @param set     查询结果集
     * @return tables
     * @throws Exception 异常
     */
    public <T extends Table> LinkedHashMap<String, T> comments(DataRuntime runtime, int index, boolean create, Catalog catalog, Schema schema, LinkedHashMap<String, T> tables, DataSet set) throws Exception {
        if (null == tables) {
            tables = new LinkedHashMap<>();
        }
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 <T extends Table> LinkedHashMap<String, T> comments(DataRuntime runtime, int index, boolean create, Catalog catalog, Schema schema, LinkedHashMap<String, T> tables, DataSet set)", 37));
        }
        return tables;
    }

    /**
     * table[结果集封装]<br/>
     * 表备注
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param index   第几条SQL 对照buildQueryTableRun返回顺序
     * @param create  上一步没有查到的,这一步是否需要新创建
     * @param catalog catalog
     * @param schema  schema
     * @param tables  上一步查询结果
     * @param set     查询结果集
     * @return tables
     * @throws Exception 异常
     */
    public <T extends Table> List<T> comments(DataRuntime runtime, int index, boolean create, Catalog catalog, Schema schema, List<T> tables, DataSet set) throws Exception {
        if (null == tables) {
            tables = new ArrayList<>();
        }
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现  <T extends Table> List<T> comments(DataRuntime runtime, int index, boolean create, Catalog catalog, Schema schema, List<T> tables, DataSet set)", 37));
        }
        return tables;
    }

    /**
     * table[命令合成]<br/>
     * 查询表DDL
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param table   表
     * @return List
     */
    @Override
    public List<Run> buildQueryDDLRun(DataRuntime runtime, Table table) throws Exception {
        //有支持直接查询DDL的在子类中实现
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 List<Run> buildQueryDDLRun(DataRuntime runtime, Table table)", 37));
        }
        return new ArrayList<>();
    }

    /**
     * table[结果集封装]<br/>
     * 查询表DDL
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param index   第几条SQL 对照 buildQueryDDLRun 返回顺序
     * @param table   表
     * @param ddls    上一步查询结果
     * @param set     sql执行的结果集
     * @return List
     */
    @Override
    public List<String> ddl(DataRuntime runtime, int index, Table table, List<String> ddls, DataSet set) {
        if (null == ddls) {
            ddls = new ArrayList<>();
        }
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 List<String> ddl(DataRuntime runtime, int index, Table table, List<String> ddls, DataSet set)", 37));
        }
        return ddls;
    }

    /* *****************************************************************************************************************
     * 													column
     * -----------------------------------------------------------------------------------------------------------------
     * [调用入口]
     * <T extends Column> LinkedHashMap<String, T> columns(DataRuntime runtime, String random, boolean greedy, Table table, boolean primary);
     * <T extends Column> List<T> columns(DataRuntime runtime, String random, boolean greedy, Catalog catalog, Schema schema, String table);
     * [命令合成]
     * List<Run> buildQueryColumnRun(DataRuntime runtime, Table table, boolean metadata) throws Exception;
     * [结果集封装]
     * <T extends Column> LinkedHashMap<String, T> columns(DataRuntime runtime, int index, boolean create, Table table, LinkedHashMap<String, T> columns, DataSet set) throws Exception;
     * <T extends Column> List<T> columns(DataRuntime runtime, int index, boolean create, Table table, List<T> columns, DataSet set) throws Exception;
     * <T extends Column> LinkedHashMap<String, T> columns(DataRuntime runtime, boolean create, LinkedHashMap<String, T> columns, Table table, String pattern) throws Exception;
     ******************************************************************************************************************/

    /**
     * column[调用入口]<br/>
     * 查询表结构(多方法合成)
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param random  用来标记同一组命令
     * @param greedy  贪婪模式 true:如果不填写catalog或schema则查询全部 false:只在当前catalog和schema中查询
     * @param table   表
     * @param primary 是否检测主键
     * @param <T>     Column
     * @return Column
     */
    public <T extends Column> LinkedHashMap<String, T> columns(DataRuntime runtime, String random, boolean greedy, Table table, boolean primary) {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 <T extends Column> LinkedHashMap<String, T> columns(DataRuntime runtime, String random, boolean greedy, Table table, boolean primary)", 37));
        }
        return new LinkedHashMap<>();
    }

    /**
     * column[调用入口]<br/>
     * 查询所有表的列
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param random  用来标记同一组命令
     * @param greedy  贪婪模式 true:如果不填写catalog或schema则查询全部 false:只在当前catalog和schema中查询
     * @param catalog catalog
     * @param schema  schema
     * @param table   查询所有表时 输入null
     * @param <T>     Column
     * @return List
     */
    public <T extends Column> List<T> columns(DataRuntime runtime, String random, boolean greedy, Catalog catalog, Schema schema, String table) {
        List<T> columns = new ArrayList<>();
        long fr = System.currentTimeMillis();
        if (null == random) {
            random = random(runtime);
        }
        Table tab = new Table(table);
        tab.setCatalog(catalog);
        tab.setSchema(schema);
        if (BasicUtil.isEmpty(catalog) && BasicUtil.isEmpty(schema) && !greedy) {
            checkSchema(runtime, tab);
        }
        //根据系统表查询
        try {
            List<Run> runs = buildQueryColumnRun(runtime, tab, false);
            if (null != runs) {
                int idx = 0;
                for (Run run : runs) {
//                    DataSet set = select(runtime, random, true, (String) null, new DefaultConfigStore().keyCase(KeyAdapter.KEY_CASE.PUT_UPPER), run);
//                    columns = columns(runtime, idx, true, tab, columns, set);
                    idx++;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return columns;
    }

    /**
     * column[命令合成]<br/>
     * 查询表上的列
     *
     * @param runtime  运行环境主要包含驱动适配器 数据源或客户端
     * @param table    表
     * @param metadata 是否根据metadata(true:SELECT * FROM T WHERE 1=0,false:查询系统表)
     * @return sqls
     */
    @Override
    public List<Run> buildQueryColumnRun(DataRuntime runtime, Table table, boolean metadata) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 List<Run> buildQueryColumnRun(DataRuntime runtime, Table table, boolean metadata)", 37));
        }
        return new ArrayList<>();
    }

    /**
     * column[结果集封装](方法1)<br/>
     * 根据系统表查询SQL获取表结构
     * 根据查询结果集构造Column
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param index   第几条SQL 对照 buildQueryColumnRun返回顺序
     * @param create  上一步没有查到的,这一步是否需要新创建
     * @param table   表
     * @param columns 上一步查询结果
     * @param set     系统表查询SQL结果集
     * @return columns
     * @throws Exception 异常
     */
    public <T extends Column> LinkedHashMap<String, T> columns(DataRuntime runtime, int index, boolean create, Table table, LinkedHashMap<String, T> columns, DataSet set) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 <T extends Column> LinkedHashMap<String, T> columns(DataRuntime runtime, int index, boolean create, Table table, LinkedHashMap<String, T> columns, DataSet set)", 37));
        }
        return new LinkedHashMap<>();
    }

    /**
     * column[结果集封装](方法1)<br/>
     * 根据系统表查询SQL获取表结构
     * 根据查询结果集构造Column
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param index   第几条SQL 对照 buildQueryColumnRun返回顺序
     * @param create  上一步没有查到的,这一步是否需要新创建
     * @param table   表
     * @param columns 上一步查询结果
     * @param set     系统表查询SQL结果集
     * @return columns
     * @throws Exception 异常
     */
    public <T extends Column> List<T> columns(DataRuntime runtime, int index, boolean create, Table table, List<T> columns, DataSet set) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 <T extends Column> List<T> columns(DataRuntime runtime, int index, boolean create, Table table, List<T> columns, DataSet set)", 37));
        }
        return new ArrayList<>();
    }

    /**
     * primary[命令合成]<br/>
     * 查询表上的主键
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param table   表
     * @return sqls
     */
    public List<Run> buildQueryPrimaryRun(DataRuntime runtime, Table table) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 List<Run> buildQueryPrimaryRun(DataRuntime runtime, Table table)", 37));
        }
        return new ArrayList<>();
    }

    /**
     * index[命令合成]<br/>
     * 查询表上的索引
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param table   表
     * @param name    名称
     * @return sqls
     */
    @Override
    public List<Run> buildQueryIndexRun(DataRuntime runtime, Table table, String name) {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 List<Run> buildQueryIndexRun(DataRuntime runtime, Table table, String name)", 37));
        }
        return new ArrayList<>();
    }

    /**
     * 根据 catalog, schema, name检测tables集合中是否存在
     *
     * @param tables  tables
     * @param catalog catalog
     * @param schema  schema
     * @param name    name
     * @param <T>     Table
     * @return 如果存在则返回Table 不存在则返回null
     */
    public <T extends Table> T table(List<T> tables, Catalog catalog, Schema schema, String name) {
        if (null != tables) {
            for (T table : tables) {
                if ((null == catalog || catalog.equal(table.getCatalog())) && (null == schema || schema.equal(table.getSchema())) && table.getName().equalsIgnoreCase(name)) {
                    return table;
                }
            }
        }
        return null;
    }

    /**
     * 根据 catalog, name检测schemas集合中是否存在
     *
     * @param schemas schemas
     * @param catalog catalog
     * @param name    name
     * @param <T>     Table
     * @return 如果存在则返回 Schema 不存在则返回null
     */
    public <T extends Schema> T schema(List<T> schemas, Catalog catalog, String name) {
        if (null != schemas) {
            for (T schema : schemas) {
                if ((null == catalog || catalog.equal(schema.getCatalog())) && schema.getName().equalsIgnoreCase(name)) {
                    return schema;
                }
            }
        }
        return null;
    }

    /**
     * 根据 name检测catalogs集合中是否存在
     *
     * @param catalogs catalogs
     * @param name     name
     * @param <T>      Table
     * @return 如果存在则返回 Catalog 不存在则返回null
     */
    public <T extends Catalog> T catalog(List<T> catalogs, String name) {
        if (null != catalogs) {
            for (T catalog : catalogs) {
                if (catalog.getName().equalsIgnoreCase(name)) {
                    return catalog;
                }
            }
        }
        return null;
    }

    /**
     * 根据 name检测databases集合中是否存在
     *
     * @param databases databases
     * @param name      name
     * @param <T>       Table
     * @return 如果存在则返回 Database 不存在则返回null
     */
    public <T extends Database> T database(List<T> databases, String name) {
        if (null != databases) {
            for (T database : databases) {
                if (database.getName().equalsIgnoreCase(name)) {
                    return database;
                }
            }
        }
        return null;
    }
    /* *****************************************************************************************************************
     *
     * 													DDL
     *
     * =================================================================================================================
     * database			: 数据库
     * table			: 表
     * master table		: 主表
     * partition table	: 分区表
     * column			: 列
     * tag				: 标签
     * primary key      : 主键
     * foreign key		: 外键
     * index			: 索引
     * constraint		: 约束
     * trigger		    : 触发器
     * procedure        : 存储过程
     * function         : 函数
     ******************************************************************************************************************/


    /**
     * table[调用入口]<br/>
     * 创建表,执行的SQL通过meta.ddls()返回
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param meta    表
     * @return boolean 是否执行成功
     * @throws Exception DDL异常
     */
    public boolean create(DataRuntime runtime, Table meta) throws Exception {
        boolean result = false;
        checkSchema(runtime, meta);
        List<Run> runs = buildCreateRun(runtime, meta);
        long fr = System.currentTimeMillis();
        try {
            result = execute(runtime, meta, runs);
        } finally {
            log.info("[name:{}][cmds:{}][result:{}][执行耗时:{}ms]", meta.getName(), runs.size(), result, System.currentTimeMillis() - fr);
        }
        return result;
    }

    public boolean alter(DataRuntime runtime, Table meta) throws Exception {
        boolean result = true;
        List<Run> runs = new ArrayList<>();
        Table update = (Table) meta.getUpdate();

        String name = meta.getName();
        String uname = update.getName();
        checkSchema(runtime, meta);
        checkSchema(runtime, update);
        if (!name.equalsIgnoreCase(uname)) {
            result = rename(runtime, meta, uname);
            meta.setName(uname);
        }
        if (!result) {
            return result;
        }
        //修改表备注
        String comment = update.getComment();
        if (!comment.equals(meta.getComment())) {
            if (BasicUtil.isNotEmpty(meta.getComment())) {
                runs.addAll(buildChangeCommentRun(runtime, update));
            } else {
                runs.addAll(buildAppendCommentRun(runtime, update));
            }
            long fr = System.currentTimeMillis();
            try {
                result = execute(runtime, meta, runs);
            } finally {
                long millis = System.currentTimeMillis() - fr;
                log.info("修改表信息成功 table = {}，消耗 = {}", meta, millis);

            }
        }
        return result;
    }

    /**
     * table[调用入口]<br/>
     * 删除表,执行的SQL通过meta.ddls()返回
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param meta    表
     * @return boolean 是否执行成功
     * @throws Exception DDL异常
     */

    public boolean drop(DataRuntime runtime, Table meta) throws Exception {
        boolean result = false;
        ACTION.DDL action = ACTION.DDL.TABLE_DROP;
        String random = random(runtime);

        checkSchema(runtime, meta);
        List<Run> runs = buildDropRun(runtime, meta);

        long fr = System.currentTimeMillis();
        try {
//            result = execute(runtime, random, meta, action, runs);
        } finally {
            long millis = System.currentTimeMillis() - fr;
            if (runs.size() > 1 && ConfigTable.IS_LOG_SQL_TIME && log.isInfoEnabled()) {
                log.info("{}[action:{}][name:{}][cmds:{}][result:{}][执行耗时:{}ms]", random, action, meta.getName(), runs.size(), result, millis);
            }

        }
        return result;
    }

    /**
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param origin  原表
     * @param name    新名称
     * @return boolean 是否执行成功
     * @throws Exception DDL异常
     */

    public boolean rename(DataRuntime runtime, Table origin, String name) throws Exception {
        boolean result = false;
        ACTION.DDL action = ACTION.DDL.TABLE_RENAME;
        String random = random(runtime);
        origin.setNewName(name);
        checkSchema(runtime, origin);
        List<Run> runs = buildRenameRun(runtime, origin);
        long fr = System.currentTimeMillis();
        try {
            result = execute(runtime, origin, runs);
        } finally {
            long millis = System.currentTimeMillis() - fr;
            if (runs.size() > 1 && ConfigTable.IS_LOG_SQL_TIME && log.isInfoEnabled()) {
                log.info("{}[action:{}][name:{}][rename:{}][cmds:{}][result:{}][执行耗时:{}ms]", random, action, origin.getName(), name, runs.size(), result, millis);
            }
        }
        return result;
    }


    /**
     * table[命令合成-子流程]<br/>
     * 部分数据库在创建主表时用主表关键字(默认)，部分数据库普通表主表子表都用table，部分数据库用collection、timeseries等
     *
     * @param meta 表
     * @return String
     */
    public String keyword(Table meta) {
        return meta.getKeyword();
    }


    /**
     * table[命令合成-子流程]<br/>
     * 修改备注
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param meta    表
     * @return sql
     * @throws Exception 异常
     */
    @Override
    public List<Run> buildChangeCommentRun(DataRuntime runtime, Table meta) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 List<Run> buildChangeCommentRun(DataRuntime runtime, Table meta)", 37));
        }
        return new ArrayList<>();
    }

    /**
     * table[命令合成-子流程]<br/>
     * 定义表的主键标识,在创建表的DDL结尾部分(注意不要跟列定义中的主键重复)
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param builder builder
     * @param meta    表
     * @return StringBuilder
     */
    @Override
    public StringBuilder primary(DataRuntime runtime, StringBuilder builder, Table meta) {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 StringBuilder primary(DataRuntime runtime, StringBuilder builder, Table meta)", 37));
        }
        return builder;
    }

    /**
     * table[命令合成-子流程]<br/>
     * 编码
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param builder builder
     * @param meta    表
     * @return StringBuilder
     */
    public StringBuilder charset(DataRuntime runtime, StringBuilder builder, Table meta) {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 StringBuilder charset(DataRuntime runtime, StringBuilder builder, Table meta)", 37));
        }
        return builder;
    }

    /**
     * table[命令合成-子流程]<br/>
     * 备注
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param builder builder
     * @param meta    表
     * @return StringBuilder
     */
    @Override
    public StringBuilder comment(DataRuntime runtime, StringBuilder builder, Table meta) {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 StringBuilder comment(DataRuntime runtime, StringBuilder builder, Table meta)", 37));
        }
        return builder;
    }

    @Override
    public boolean alter(DataRuntime runtime, Table table, Column column) throws Exception {
        boolean result;
        checkSchema(runtime, table);
        if (column.isDrop()) {
            column.setAction(ACTION.DDL.COLUMN_DROP);
        } else {
            HashMap<String, Column> columns = columns(runtime, table, null);
            Column existColumn = columns.get(column.getName());
            Column target = column.getUpdate();
            if (target == null) {
                throw new RuntimeException("修改列表信息失败，无法获取修改后信息 column = " + column.getName());
            }
            if (target.equals(existColumn)) {
                log.error("数据列数据未修改 source = {},target = {}", existColumn, target);
                return false;
            }
            if (existColumn != null) {
                column = existColumn;
                column.setAction(ACTION.DDL.COLUMN_ALTER);
            } else {
                column.setAction(ACTION.DDL.COLUMN_ADD);
            }
        }
        fillSchema(table, column);
        column.setTable(table);
        List<Run> runs = buildAlterRun(runtime, table, Collections.singleton(column));
        long fr = System.currentTimeMillis();
        try {
            result = execute(runtime, table, runs);
        } catch (Exception e) {
            log.error("[修改Column执行异常] table = {},column = {}", table, column);
            throw e;
        } finally {
            long millis = System.currentTimeMillis() - fr;
            log.info("执行修改表的列数据成功，table = {},column = {},[执行耗时:{}ms]", table.getTableName(), column.getName(), millis);
        }
        return result;
    }

    /**
     * column[调用入口]<br/>
     * 删除列,执行的SQL通过meta.ddls()返回
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param meta    列
     * @return boolean 是否执行成功
     * @throws Exception DDL异常
     */
    public boolean drop(DataRuntime runtime, Column meta) throws Exception {
        boolean result = false;
        ACTION.DDL action = ACTION.DDL.COLUMN_DROP;
        String random = random(runtime);
        checkSchema(runtime, meta);
        List<Run> runs = buildDropRun(runtime, meta);
        long fr = System.currentTimeMillis();
        try {
//            result = execute(runtime, random, meta, action, runs);
        } finally {
            long millis = System.currentTimeMillis() - fr;
            if (runs.size() > 1 && ConfigTable.IS_LOG_SQL && log.isInfoEnabled()) {
                log.info("{}[action:{}][table:{}][name:{}][cmds:{}][result:{}][执行耗时:{}ms]", random, action, meta.getTableName(true), meta.getName(), runs.size(), result, millis);
            }
        }
        return result;
    }

    /**
     * column[调用入口]<br/>
     * 重命名列,执行的SQL通过meta.ddls()返回
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param origin  列
     * @param name    新名称
     * @return boolean 是否执行成功
     * @throws Exception DDL异常
     */
    public boolean rename(DataRuntime runtime, Column origin, String name) throws Exception {
        boolean result = false;
        ACTION.DDL action = ACTION.DDL.COLUMN_RENAME;
        String random = random(runtime);
        origin.setNewName(name);
        checkSchema(runtime, origin);
        List<Run> runs = buildRenameRun(runtime, origin);
        long fr = System.currentTimeMillis();
        try {
//            result = execute(runtime, random, origin, action, runs);
        } finally {
            long millis = System.currentTimeMillis() - fr;
            if (runs.size() > 1 && ConfigTable.IS_LOG_SQL && log.isInfoEnabled()) {
                log.info("{}[action:{}][table:{}][name:{}][rename:{}][cmds:{}][result:{}][执行耗时:{}ms]", random, action, origin.getTableName(true), origin.getName(), name, runs.size(), result, millis);
            }

        }
        return result;
    }


    /**
     * column[命令合成]<br/>
     * 添加列
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param meta    列
     * @param slice   是否只生成片段(不含alter table部分，用于DDL合并)
     * @return String
     */
    @Override
    public List<Run> buildAddRun(DataRuntime runtime, Column meta, boolean slice) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 List<Run> buildAddRun(DataRuntime runtime, Column meta, boolean slice)", 37));
        }
        return new ArrayList<>();
    }

    @Override
    public List<Run> buildAddRun(DataRuntime runtime, Column meta) throws Exception {
        return buildAddRun(runtime, meta, false);
    }

    /**
     * column[命令合成]<br/>
     * 删除列
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param meta    列
     * @param slice   是否只生成片段(不含alter table部分，用于DDL合并)
     * @return String
     */
    @Override
    public List<Run> buildDropRun(DataRuntime runtime, Column meta, boolean slice) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 List<Run> buildDropRun(DataRuntime runtime, Column meta, boolean slice)", 37));
        }
        return new ArrayList<>();
    }

    @Override
    public List<Run> buildDropRun(DataRuntime runtime, Column meta) throws Exception {
        return buildDropRun(runtime, meta, false);
    }

    /**
     * column[命令合成]<br/>
     * 修改列名
     * 一般不直接调用,如果需要由buildAlterRun内部统一调用
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param meta    列
     * @return String
     */
    @Override
    public List<Run> buildRenameRun(DataRuntime runtime, Column meta) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 List<Run> buildRenameRun(DataRuntime runtime, Column meta)", 37));
        }
        return new ArrayList<>();
    }


    /**
     * column[命令合成-子流程]<br/>
     * 修改数据类型
     * 一般不直接调用,如果需要由buildAlterRun内部统一调用
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param meta    列
     * @return String
     */
    @Override
    public List<Run> buildChangeTypeRun(DataRuntime runtime, Column meta) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 List<Run> buildChangeTypeRun(DataRuntime runtime, Column meta)", 37));
        }
        return new ArrayList<>();
    }

    /**
     * column[命令合成-子流程]<br/>
     * 修改表的关键字
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @return String
     */
    @Override
    public String alterColumnKeyword(DataRuntime runtime) {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 String alterColumnKeyword(DataRuntime runtime)", 37));
        }
        return null;
    }

    /**
     * column[命令合成-子流程]<br/>
     * 添加列引导<br/>
     * alter table sso_user [add column] type_code int
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param builder StringBuilder
     * @param meta    列
     * @return String
     */
    public StringBuilder addColumnGuide(DataRuntime runtime, StringBuilder builder, Column meta) {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 StringBuilder addColumnGuide(DataRuntime runtime, StringBuilder builder, Column meta)", 37));
        }
        return builder;
    }


    /**
     * column[命令合成-子流程]<br/>
     * 删除列引导<br/>
     * alter table sso_user [drop column] type_code
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param builder StringBuilder
     * @param meta    列
     * @return String
     */
    public StringBuilder dropColumnGuide(DataRuntime runtime, StringBuilder builder, Column meta) {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 StringBuilder dropColumnGuide(DataRuntime runtime, StringBuilder builder, Column meta)", 37));
        }
        return builder;
    }

    /**
     * column[命令合成-子流程]<br/>
     * 修改默认值
     * 一般不直接调用,如果需要由buildAlterRun内部统一调用
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param meta    列
     * @return String
     */
    @Override
    public List<Run> buildChangeDefaultRun(DataRuntime runtime, Column meta) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 List<Run> buildChangeDefaultRun(DataRuntime runtime, Column meta)", 37));
        }
        return new ArrayList<>();
    }


    /**
     * column[命令合成-子流程]<br/>
     * 修改非空限制
     * 一般不直接调用,如果需要由buildAlterRun内部统一调用
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param meta    列
     * @return String
     */
    @Override
    public List<Run> buildChangeNullableRun(DataRuntime runtime, Column meta) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 List<Run> buildChangeNullableRun(DataRuntime runtime, Column meta)", 37));
        }
        return new ArrayList<>();
    }

    /**
     * column[命令合成-子流程]<br/>
     * 修改备注
     * 一般不直接调用,如果需要由buildAlterRun内部统一调用
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param meta    列
     * @return String
     */
    @Override
    public List<Run> buildChangeCommentRun(DataRuntime runtime, Column meta) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 List<Run> buildChangeCommentRun(DataRuntime runtime, Column meta)", 37));
        }
        return new ArrayList<>();
    }

    /**
     * column[命令合成-子流程]<br/>
     * 添加表备注(表创建完成后调用,创建过程能添加备注的不需要实现)
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param column 列
     * @return sql
     * @throws Exception 异常
     */
    /**
     * 添加表备注(表创建完成后调用,创建过程能添加备注的不需要实现)
     *
     * @param meta 列
     * @return sql
     * @throws Exception 异常
     */
    @Override
    public List<Run> buildAppendCommentRun(DataRuntime runtime, Column meta) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 List<Run> buildAppendCommentRun(DataRuntime runtime, Column meta)", 37));
        }
        return new ArrayList<>();
    }


    /**
     * column[命令合成-子流程]<br/>
     * 取消自增
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param meta    列
     * @return sql
     * @throws Exception 异常
     */
    public List<Run> buildDropAutoIncrement(DataRuntime runtime, Column meta) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 List<Run> buildDropAutoIncrement(DataRuntime runtime, Column meta)", 37));
        }
        return new ArrayList<>();
    }

    /**
     * column[命令合成-子流程]<br/>
     * 定义列，依次拼接下面几个属性注意不同数据库可能顺序不一样
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param builder builder
     * @param meta    列
     * @return StringBuilder
     */
    @Override
    public StringBuilder define(DataRuntime runtime, StringBuilder builder, Column meta) {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 StringBuilder define(DataRuntime runtime, StringBuilder builder, Column meta)", 37));
        }
        return builder;
    }

    /**
     * column[命令合成-子流程]<br/>
     * 列定义:创建或删除列之前  检测表是否存在
     * IF NOT EXISTS
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param builder builder
     * @param exists  exists
     * @return StringBuilder
     */
    @Override
    public StringBuilder checkColumnExists(DataRuntime runtime, StringBuilder builder, boolean exists) {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 checkColumnExists(DataRuntime runtime, StringBuilder builder, boolean exists)", 37));
        }
        return builder;
    }

    /**
     * column[命令合成-子流程]<br/>
     * 列定义:数据类型
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param builder builder
     * @param meta    列
     * @return StringBuilder
     */
    @Override
    public StringBuilder type(DataRuntime runtime, StringBuilder builder, Column meta) {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 StringBuilder type(DataRuntime runtime, StringBuilder builder, Column meta)", 37));
        }
        return builder;
    }

    /**
     * column[命令合成-子流程]<br/>
     * 列定义:数据类型定义
     *
     * @param runtime           运行环境主要包含驱动适配器 数据源或客户端
     * @param builder           builder
     * @param meta              列
     * @param type              数据类型(已经过转换)
     * @param isIgnorePrecision 是否忽略长度
     * @param isIgnoreScale     是否忽略小数
     * @return StringBuilder
     */
    @Override
    public StringBuilder type(DataRuntime runtime, StringBuilder builder, Column meta, String type, boolean isIgnorePrecision, boolean isIgnoreScale) {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 StringBuilder type(DataRuntime runtime, StringBuilder builder, Column meta, String type, boolean isIgnorePrecision, boolean isIgnoreScale)", 37));
        }
        return builder;
    }


    /**
     * column[命令合成-子流程]<br/>
     * 列定义:是否忽略长度
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param meta    列
     * @return boolean
     */
    @Override
    public boolean isIgnorePrecision(DataRuntime runtime, Column meta) {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 boolean isIgnorePrecision(DataRuntime runtime, Column meta)", 37));
        }
        return false;
    }

    /**
     * column[命令合成-子流程]<br/>
     * 列定义:是否忽略精度
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param meta    列
     * @return boolean
     */
    @Override
    public boolean isIgnoreScale(DataRuntime runtime, Column meta) {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 boolean isIgnoreScale(DataRuntime runtime, Column meta)", 37));
        }
        return false;
    }

    /**
     * column[命令合成-子流程]<br/>
     * 列定义:是否忽略长度
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param type    列数据类型
     * @return Boolean 检测不到时返回null
     */
    @Override
    public Boolean checkIgnorePrecision(DataRuntime runtime, String type) {
        type = type.toUpperCase();
        if (type.contains("INT")) {
            return false;
        }
        if (type.contains("DATE")) {
            return true;
        }
        if (type.contains("TIME")) {
            return true;
        }
        if (type.contains("YEAR")) {
            return true;
        }
        if (type.contains("TEXT")) {
            return true;
        }
        if (type.contains("BLOB")) {
            return true;
        }
        if (type.contains("JSON")) {
            return true;
        }
        if (type.contains("POINT")) {
            return true;
        }
        if (type.contains("LINE")) {
            return true;
        }
        if (type.contains("POLYGON")) {
            return true;
        }
        if (type.contains("GEOMETRY")) {
            return true;
        }
        return null;
    }

    /**
     * column[命令合成-子流程]<br/>
     * 列定义:是否忽略精度
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param type    列数据类型
     * @return Boolean 检测不到时返回null
     */
    @Override
    public Boolean checkIgnoreScale(DataRuntime runtime, String type) {
        type = type.toUpperCase();
        if (type.contains("INT")) {
            return true;
        }
        if (type.contains("DATE")) {
            return true;
        }
        if (type.contains("TIME")) {
            return true;
        }
        if (type.contains("YEAR")) {
            return true;
        }
        if (type.contains("TEXT")) {
            return true;
        }
        if (type.contains("BLOB")) {
            return true;
        }
        if (type.contains("JSON")) {
            return true;
        }
        if (type.contains("POINT")) {
            return true;
        }
        if (type.contains("LINE")) {
            return true;
        }
        if (type.contains("POLYGON")) {
            return true;
        }
        if (type.contains("GEOMETRY")) {
            return true;
        }
        return null;
    }

    /**
     * column[命令合成-子流程]<br/>
     * 列定义:非空
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param builder builder
     * @param meta    列
     * @return StringBuilder
     */
    @Override
    public StringBuilder nullable(DataRuntime runtime, StringBuilder builder, Column meta) {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 StringBuilder nullable(DataRuntime runtime, StringBuilder builder, Column meta)", 37));
        }
        return builder;
    }

    /**
     * column[命令合成-子流程]<br/>
     * 列定义:备注
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param builder builder
     * @param meta    列
     * @return StringBuilder
     */
    @Override
    public StringBuilder comment(DataRuntime runtime, StringBuilder builder, Column meta) {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 StringBuilder comment(DataRuntime runtime, StringBuilder builder, Column meta)", 37));
        }
        return builder;
    }

    /**
     * column[命令合成-子流程]<br/>
     * 列定义:编码
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param builder builder
     * @param meta    列
     * @return StringBuilder
     */
    @Override
    public StringBuilder charset(DataRuntime runtime, StringBuilder builder, Column meta) {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 StringBuilder charset(DataRuntime runtime, StringBuilder builder, Column meta)", 37));
        }
        return builder;
    }

    /**
     * column[命令合成-子流程]<br/>
     * 列定义:默认值
     *
     * @param builder builder
     * @param meta    列
     * @return StringBuilder
     */
    @Override
    public StringBuilder defaultValue(DataRuntime runtime, StringBuilder builder, Column meta) {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 StringBuilder defaultValue(DataRuntime runtime, StringBuilder builder, Column meta)", 37));
        }
        return builder;
    }

    /**
     * column[命令合成-子流程]<br/>
     * 列定义:定义列的主键标识(注意不要跟表定义中的主键重复)
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param builder builder
     * @param meta    列
     * @return StringBuilder
     */
    @Override
    public StringBuilder primary(DataRuntime runtime, StringBuilder builder, Column meta) {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 StringBuilder primary(DataRuntime runtime, StringBuilder builder, Column meta)", 37));
        }
        return builder;
    }

    /**
     * column[命令合成-子流程]<br/>
     * 列定义:递增列
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param builder builder
     * @param meta    列
     * @return StringBuilder
     */
    @Override
    public StringBuilder increment(DataRuntime runtime, StringBuilder builder, Column meta) {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 StringBuilder increment(DataRuntime runtime, StringBuilder builder, Column meta)", 37));
        }
        return builder;
    }

    /**
     * column[命令合成-子流程]<br/>
     * 列定义:更新行事件
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param builder builder
     * @param meta    列
     * @return StringBuilder
     */
    @Override
    public StringBuilder onupdate(DataRuntime runtime, StringBuilder builder, Column meta) {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 StringBuilder onupdate(DataRuntime runtime, StringBuilder builder, Column meta)", 37));
        }
        return builder;
    }

    /**
     * primary[调用入口]<br/>
     * 添加主键
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param meta    主键
     * @return 是否执行成功
     * @throws Exception 异常
     */
    public boolean add(DataRuntime runtime, PrimaryKey meta) throws Exception {
        boolean result = false;
        ACTION.DDL action = ACTION.DDL.PRIMARY_ADD;
        String random = random(runtime);

        checkSchema(runtime, meta);
        List<Run> runs = buildAddRun(runtime, meta);
        long fr = System.currentTimeMillis();
        try {
//            result = execute(runtime, random, meta, action, runs);
        } finally {
            long millis = System.currentTimeMillis() - fr;
            if (runs.size() > 1 && ConfigTable.IS_LOG_SQL && log.isInfoEnabled()) {
                log.info("{}[action:{}][table:{}][name:{}][cmds:{}][result:{}][执行耗时:{}ms]", random, action, meta.getTableName(true), meta.getName(), runs.size(), result, millis);
            }

        }
        return result;
    }

    /**
     * primary[调用入口]<br/>
     * 修改主键
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param table   表
     * @param origin  原主键
     * @param meta    新主键
     * @return 是否执行成功
     * @throws Exception 异常
     */
    public boolean alter(DataRuntime runtime, Table table, PrimaryKey origin, PrimaryKey meta) throws Exception {
        boolean result = false;
        ACTION.DDL action = ACTION.DDL.PRIMARY_ALTER;
        String random = random(runtime);

        checkSchema(runtime, meta);
        List<Run> runs = buildAlterRun(runtime, origin, meta);
        long fr = System.currentTimeMillis();
        try {
//            result = execute(runtime, random, table, action, runs);
        } finally {
            long millis = System.currentTimeMillis() - fr;
            if (runs.size() > 1 && ConfigTable.IS_LOG_SQL && log.isInfoEnabled()) {
                log.info("{}[action:{}][table:{}][name:{}][cmds:{}][result:{}][执行耗时:{}ms]", random, action, meta.getTableName(true), meta.getName(), runs.size(), result, millis);
            }
        }

        return result;
    }

    /**
     * primary[调用入口]<br/>
     * 删除主键
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端```
     * @param meta    主键
     * @return 是否执行成功
     * @throws Exception 异常
     */
    public boolean drop(DataRuntime runtime, PrimaryKey meta) throws Exception {
        boolean result = false;
        ACTION.DDL action = ACTION.DDL.PRIMARY_DROP;
        String random = random(runtime);
        checkSchema(runtime, meta);
        List<Run> runs = buildDropRun(runtime, meta);
        long fr = System.currentTimeMillis();
        try {
//            result = execute(runtime, random, meta, action, runs);
        } finally {
            long millis = System.currentTimeMillis() - fr;
            if (runs.size() > 1 && ConfigTable.IS_LOG_SQL && log.isInfoEnabled()) {
                log.info("{}[action:{}][table:{}][name:{}][cmds:{}][result:{}][执行耗时:{}ms]", random, action, meta.getTableName(true), meta.getName(), runs.size(), result, millis);
            }
        }
        return result;
    }

    /**
     * primary[调用入口]<br/>
     * 添加主键
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param origin  主键
     * @param name    新名称
     * @return 是否执行成功
     * @throws Exception 异常
     */
    public boolean rename(DataRuntime runtime, PrimaryKey origin, String name) throws Exception {
        boolean result = false;
        ACTION.DDL action = ACTION.DDL.PRIMARY_RENAME;
        String random = random(runtime);
        origin.setNewName(name);
        checkSchema(runtime, origin);
        List<Run> runs = buildRenameRun(runtime, origin);
        long fr = System.currentTimeMillis();
        try {
//            result = execute(runtime, random, origin, action, runs);
        } finally {
            long millis = System.currentTimeMillis() - fr;
            if (runs.size() > 1 && ConfigTable.IS_LOG_SQL && log.isInfoEnabled()) {
                log.info("{}[action:{}][table:{}][name:{}][rename:{}][cmds:{}][result:{}][执行耗时:{}ms]", random, action, origin.getTableName(true), origin.getName(), name, runs.size(), result, millis);
            }
        }
        return result;
    }

    /**
     * primary[命令合成]<br/>
     * 添加主键
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param meta    主键
     * @param slice   是否只生成片段(不含alter table部分，用于DDL合并)
     * @return String
     */
    @Override
    public List<Run> buildAddRun(DataRuntime runtime, PrimaryKey meta, boolean slice) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 List<Run> buildAddRun(DataRuntime runtime, PrimaryKey meta,  boolean slice)", 37));
        }
        return new ArrayList<>();
    }

    /**
     * primary[命令合成]<br/>
     * 修改主键
     * 有可能生成多条SQL
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param origin  原主键
     * @param meta    新主键
     * @return List
     */
    @Override
    public List<Run> buildAlterRun(DataRuntime runtime, PrimaryKey origin, PrimaryKey meta) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 List<Run> buildAlterRun(DataRuntime runtime, PrimaryKey origin, PrimaryKey meta)", 37));
        }
        return new ArrayList<>();
    }

    /**
     * primary[命令合成]<br/>
     * 删除主键
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param meta    主键
     * @param slice   是否只生成片段(不含alter table部分，用于DDL合并)
     * @return String
     */
    @Override
    public List<Run> buildDropRun(DataRuntime runtime, PrimaryKey meta, boolean slice) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 List<Run> buildDropRun(DataRuntime runtime, PrimaryKey meta, boolean slice)", 37));
        }
        return new ArrayList<>();
    }

    /**
     * primary[命令合成]<br/>
     * 修改主键名
     * 一般不直接调用,如果需要由buildAlterRun内部统一调用
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param meta    主键
     * @return String
     */
    @Override
    public List<Run> buildRenameRun(DataRuntime runtime, PrimaryKey meta) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 List<Run> buildAddRun(DataRuntime runtime, PrimaryKey meta)", 37));
        }
        return new ArrayList<>();
    }


    /* *****************************************************************************************************************
     * 													index
     * -----------------------------------------------------------------------------------------------------------------
     * [调用入口]
     * boolean add(DataRuntime runtime, Index meta)
     * boolean alter(DataRuntime runtime, Index meta)
     * boolean alter(DataRuntime runtime, Table table, Index meta)
     * boolean drop(DataRuntime runtime, Index meta)
     * boolean rename(DataRuntime runtime, Index origin, String name)
     * [命令合成]
     * List<Run> buildAddRun(DataRuntime runtime, Index meta)
     * List<Run> buildAlterRun(DataRuntime runtime, Index meta)
     * List<Run> buildDropRun(DataRuntime runtime, Index meta)
     * List<Run> buildRenameRun(DataRuntime runtime, Index meta)
     * [命令合成-子流程]
     * StringBuilder type(DataRuntime runtime, StringBuilder builder, Index meta)
     * StringBuilder comment(DataRuntime runtime, StringBuilder builder, Index meta)
     ******************************************************************************************************************/

    /**
     * index[调用入口]<br/>
     * 添加索引
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param meta    索引
     * @return 是否执行成功
     * @throws Exception 异常
     */
    @Override
    public boolean add(DataRuntime runtime, Index meta) throws Exception {
        boolean result;
        checkSchema(runtime, meta);
        Map<String, Index> idx = indexs(runtime, meta.getTable());
        if (idx.values().stream().anyMatch(index -> {
            Map<String, Column> indexCols = index.getColumns();
            Collection<String> cols = indexCols.values().stream().map(BaseMetadata::getName).toList();
            Map<String, Column> incomingIndexCols = meta.getColumns();
            Collection<String> incomingCols = incomingIndexCols.values().stream().map(BaseMetadata::getName).toList();
            return CollectionUtils.isEqualCollection(cols, incomingCols);
        })) {
            log.info("索引已存在，index = {}", meta.getName());
            return false;
        }
        List<Run> runs = buildAddRun(runtime, meta);
        long fr = System.currentTimeMillis();
        try {
            result = execute(runtime, meta, runs);
        } finally {
            long millis = System.currentTimeMillis() - fr;
            log.info("添加索引信息，table = {},index = {},[执行耗时:{}ms]", meta.getTableName(true), meta.getName(), millis);
        }
        return result;
    }

    /**
     * index[调用入口]<br/>
     * 删除索引
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param meta    索引
     * @return 是否执行成功
     * @throws Exception 异常
     */
    public boolean drop(DataRuntime runtime, Index meta) throws Exception {
        boolean result = false;
        ACTION.DDL action = ACTION.DDL.INDEX_DROP;
        String random = random(runtime);
        checkSchema(runtime, meta);
        List<Run> runs = buildDropRun(runtime, meta);
        long fr = System.currentTimeMillis();
        try {
//            result = execute(runtime, random, meta, action, runs);
        } finally {
            long millis = System.currentTimeMillis() - fr;
            if (runs.size() > 1 && ConfigTable.IS_LOG_SQL && log.isInfoEnabled()) {
                log.info("{}[action:{}][table:{}][name:{}][cmds:{}][result:{}][执行耗时:{}ms]", random, action, meta.getTableName(true), meta.getName(), runs.size(), result, millis);
            }
        }
        return result;
    }

    /**
     * index[命令合成]<br/>
     * 添加索引
     *
     * @param runtime 运行环境主要包含驱动适配器 数据源或客户端
     * @param meta    索引
     * @return String
     */
    @Override
    public List<Run> buildAddRun(DataRuntime runtime, Index meta) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug(LogUtil.format("子类(" + this.getClass().getSimpleName() + ")未实现 List<Run> buildAddRun(DataRuntime runtime, Index meta)", 37));
        }
        return new ArrayList<>();
    }

    /**
     * 转换成相应数据库类型
     *
     * @param type type
     * @return String
     */
    @Override
    public ColumnType type(String type) {
        if (null == type) {
            return null;
        }
        boolean array = false;
        if (type.startsWith("_")) {
            type = type.substring(1);
            array = true;
        }
        if (type.endsWith("[]")) {
            type = type.replace("[]", "");
            array = true;
        }
        if (type.contains(" ")) {
            type = type.split(" ")[0];//bigint unsigred
        }
        ColumnType ct = types.get(type.toUpperCase());
        if (null != ct) {
            ct.setArray(array);
        }
        return ct;
    }

    /**
     * 构造完整表名
     *
     * @param builder builder
     * @param meta    BaseMetadata
     * @return StringBuilder
     */
    @Override
    public StringBuilder name(DataRuntime runtime, StringBuilder builder, BaseMetadata meta) {
        Catalog catalog = meta.getCatalog();
        Schema schema = meta.getSchema();
        String name = meta.getName();
        if (BasicUtil.isNotEmpty(catalog)) {
            delimiter(builder, catalog).append(".");
        }
        if (BasicUtil.isNotEmpty(schema)) {
            delimiter(builder, schema).append(".");
        }
        delimiter(builder, name);
        return builder;
    }

    @Override
    public boolean isBooleanColumn(DataRuntime runtime, Column column) {
        String clazz = column.getClassName();
        if (null != clazz) {
            clazz = clazz.toLowerCase();
            if (clazz.contains("boolean")) {
                return true;
            }
        } else {
            // 如果没有同步法数据库,直接生成column可能只设置了type Name
            String type = column.getTypeName();
            if (null != type) {
                type = type.toLowerCase();
                if (type.equals("bit") || type.equals("bool")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 是否同数字
     *
     * @param column 列
     * @return boolean
     */
    @Override
    public boolean isNumberColumn(DataRuntime runtime, Column column) {
        String clazz = column.getClassName();
        if (null != clazz) {
            clazz = clazz.toLowerCase();
            if (clazz.startsWith("int") || clazz.contains("integer") || clazz.contains("long") || clazz.contains("decimal") || clazz.contains("float") || clazz.contains("double") || clazz.contains("timestamp")
                    // || clazz.contains("bit")
                    || clazz.contains("short")) {
                return true;
            }
        } else {
            // 如果没有同步法数据库,直接生成column可能只设置了type Name
            String type = column.getTypeName();
            if (null != type) {
                type = type.toLowerCase();
                if (type.startsWith("int") || type.contains("float") || type.contains("double") || type.contains("short") || type.contains("long") || type.contains("decimal") || type.contains("numeric") || type.contains("timestamp")) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean isCharColumn(DataRuntime runtime, Column column) {
        return !isNumberColumn(runtime, column) && !isBooleanColumn(runtime, column);
    }

    /**
     * 先检测rs中是否包含当前key 如果包含再取值, 取值时按keys中的大小写为准
     *
     * @param keys keys
     * @param key  key
     * @param set  ResultSet
     * @return String
     * @throws Exception 异常
     */
    protected String string(Map<String, Integer> keys, String key, ResultSet set, String def) throws Exception {
        Object value = value(keys, key, set);
        if (null != value) {
            return value.toString();
        }
        return def;
    }

    protected String string(Map<String, Integer> keys, String key, ResultSet set) throws Exception {
        return string(keys, key, set, null);
    }

    protected Integer integer(Map<String, Integer> keys, String key, ResultSet set, Integer def) throws Exception {
        Object value = value(keys, key, set);
        if (null != value) {
            return BasicUtil.parseInt(value, def);
        }
        return null;
    }

    protected Long longs(Map<String, Integer> keys, String key, ResultSet set, Long def) throws Exception {
        Object value = value(keys, key, set);
        if (null != value) {
            return BasicUtil.parseLong(value, def);
        }
        return null;
    }

    protected Boolean bool(Map<String, Integer> keys, String key, ResultSet set, Boolean def) throws Exception {
        Object value = value(keys, key, set);
        if (null != value) {
            return BasicUtil.parseBoolean(value, def);
        }
        return null;
    }

    protected Boolean bool(Map<String, Integer> keys, String key, ResultSet set, int def) throws Exception {
        Boolean defaultValue = null;
        if (def == 0) {
            defaultValue = false;
        } else if (def == 1) {
            defaultValue = true;
        }
        return bool(keys, key, set, defaultValue);
    }

    /**
     * 从resultset中根据名列取值
     *
     * @param keys 列名位置
     * @param key  列名 多个以,分隔
     * @param set  result
     * @param def  默认值
     * @return Object
     * @throws Exception Exception
     */
    protected Object value(Map<String, Integer> keys, String key, ResultSet set, Object def) throws Exception {
        String[] ks = key.split(",");
        Object result = null;
        for (String k : ks) {
            Integer index = keys.get(k);
            if (null != index && index >= 0) {
                try {
                    // db2 直接用 set.getObject(String) 可能发生 参数无效：未知列名 String
                    result = set.getObject(index);
                    if (null != result) {
                        return result;
                    }
                } catch (Exception e) {

                }
            }
        }
        return def;
    }

    protected Object value(Map<String, Integer> keys, String key, ResultSet set) throws Exception {
        return value(keys, key, set, null);
    }

    @Override
    public String getPrimaryKey(DataRuntime runtime, Object obj) {
        if (null == obj) {
            return null;
        }
        if (obj instanceof DataRow) {
            return ((DataRow) obj).getPrimaryKey();
        } else {
            return EntityAdapterProxy.primaryKey(obj.getClass(), true);
        }
    }

    @Override
    public Object getPrimaryValue(DataRuntime runtime, Object obj) {
        if (null == obj) {
            return null;
        }
        if (obj instanceof DataRow) {
            return ((DataRow) obj).getPrimaryValue();
        } else {
            return EntityAdapterProxy.primaryValue(obj);
        }
    }

    public String parseTable(String table) {
        if (null == table) {
            return table;
        }
        table = table.replace(getDelimiterFr(), "").replace(getDelimiterTo(), "");
        table = DataSourceUtil.parseDataSource(table, null);
        if (table.contains(".")) {
            String tmps[] = table.split("\\.");
            table = SQLUtil.delimiter(tmps[0], getDelimiterFr(), getDelimiterTo()) + "." + SQLUtil.delimiter(tmps[1], getDelimiterFr(), getDelimiterTo());
        } else {
            table = SQLUtil.delimiter(table, getDelimiterFr(), getDelimiterTo());
        }
        return table;
    }


    /**
     * 写入数据库前类型转换<br/>
     *
     * @param metadata    Column 用来定位数据类型
     * @param placeholder 是否占位符
     * @param value       value
     * @return Object
     */
    @Override
    public Object write(DataRuntime runtime, Column metadata, Object value, boolean placeholder) {
        if (null == value || "NULL".equals(value)) {
            return null;
        }
        Object result = null;
        ColumnType columnType = null;
        DataWriter writer = null;
        boolean isArray = false;
        if (null != metadata) {
            isArray = metadata.isArray();
            //根据列类型
            columnType = metadata.getColumnType();
            if (null != columnType) {
                writer = writer(columnType);
            }
            if (null == writer) {
                String typeName = metadata.getTypeName();
                if (null != typeName) {
                    writer = writer(typeName);
                    if (null != columnType) {
                        writer = writer(type(typeName.toUpperCase()));
                    }
                }
            }
        }
        if (null == columnType) {
            columnType = type(value.getClass().getSimpleName());
        }
        if (null != columnType) {
            Class writeClass = columnType.compatible();
            value = ConvertAdapter.convert(value, writeClass, isArray);
        }

        if (null != columnType) {//根据列类型定位writer
            writer = writer(columnType);
        }
        if (null == writer && null != value) {//根据值类型定位writer
            writer = writer(value.getClass());
        }
        if (null != writer) {
            result = writer.write(value, placeholder);
        }
        if (null != result) {
            return result;
        }
        if (null != columnType) {
            result = columnType.write(value, null, false);
        }
        if (null != result) {
            return result;
        }
        //根据值类型
        if (!placeholder) {
            if (BasicUtil.isNumber(value) || "NULL".equals(value)) {
                result = value;
            } else {
                result = "'" + value + "'";
            }
        }

        return result;
    }

    /**
     * 从数据库中读取数据<br/>
     * 先由子类根据metadata.typeName(CHAR,INT)定位到具体的数据库类型ColumnType<br/>
     * 如果定准成功由CoumnType根据class转换(class可不提供)<br/>
     * 如果没有定位到ColumnType再根据className(String,BigDecimal)定位到JavaType<br/>
     * 如果定准失败或转换失败(返回null)再由父类转换<br/>
     * 如果没有提供metadata和class则根据value.class<br/>
     * 常用类型jdbc可以自动转换直接返回就可以(一般子类DataType返回null父类原样返回)<br/>
     * 不常用的如json/point/polygon/blob等转换成anyline对应的类型<br/>
     *
     * @param metadata Column 用来定位数据类型
     * @param value    value
     * @param clazz    目标数据类型(给entity赋值时应该指定属性class, DataRow赋值时可以通过JDBChandler指定class)
     * @return Object
     */
    @Override
    public Object read(DataRuntime runtime, Column metadata, Object value, Class clazz) {
        //Object result = ConvertAdapter.convert(value, clazz);
        Object result = value;
        if (null == value) {
            return null;
        }
        DataReader reader = null;
        ColumnType ctype = null;
        if (null != metadata) {
            ctype = metadata.getColumnType();
            if (null != ctype) {
                reader = reader(ctype);
            }
            if (null == reader) {
                String typeName = metadata.getTypeName();
                if (null != typeName) {
                    reader = reader(typeName);
                    if (null == reader) {
                        reader = reader(type(typeName));
                    }
                }
            }
        }
        if (null == reader) {
            reader = reader(value.getClass());
        }
        if (null != reader) {
            result = reader.read(value);
        }
        if (null == reader || null == result) {
            if (null != ctype) {
                result = ctype.read(value, null, clazz);
            }
        }
        return result;
    }

    @Override
    public void value(DataRuntime runtime, StringBuilder builder, Object obj, String key) {
        Object value = null;
        if (obj instanceof DataRow) {
            value = ((DataRow) obj).get(key);
        } else {
            if (EntityAdapterProxy.hasAdapter(obj.getClass())) {
                Field field = EntityAdapterProxy.field(obj.getClass(), key);
                value = BeanUtil.getFieldValue(obj, field);
            } else {
                value = BeanUtil.getFieldValue(obj, key);
            }
        }
        if (null != value) {
            if (value instanceof SQL_BUILD_IN_VALUE) {
//                builder.append(value(runtime, null, (SQL_BUILD_IN_VALUE) value));
            } else {
                ColumnType type = type(value.getClass().getName());
                if (null != type) {
                    value = type.write(value, null, false);
                }
                builder.append(value);

            }
        } else {
            builder.append("null");
        }
    }

    @Override
    public boolean convert(DataRuntime runtime, Catalog catalog, Schema schema, String table, RunValue value) {
        boolean result = false;
        if (ConfigTable.IS_AUTO_CHECK_METADATA) {
            LinkedHashMap<String, Column> columns = columns(runtime, null, false, new Table(catalog, schema, table), false);
            result = convert(runtime, columns, value);
        } else {
            result = convert(runtime, (Column) null, value);
        }
        return result;
    }

    /**
     * 设置参数值,主要根据数据类型格执行式化，如对象,list,map等插入json列
     *
     * @param run     最终待执行的命令和参数(如果是JDBC环境就是SQL)
     * @param compare 比较方式 默认 equal 多个值默认 in
     * @param column  列
     * @param value   value
     */
    @Override
    public void addRunValue(DataRuntime runtime, Run run, Compare compare, Column column, Object value) {
        boolean split = ConfigTable.IS_AUTO_SPLIT_ARRAY;
        if (ConfigTable.IS_AUTO_CHECK_METADATA) {
            String type = null;
            if (null != column) {
                type = column.getTypeName();
                if (null == type && BasicUtil.isNotEmpty(run.getTable())) {
                    LinkedHashMap<String, Column> columns = columns(runtime, null, false, new Table(run.getTable()), false);
                    column = columns.get(column.getName().toUpperCase());
                    if (null != column) {
                        type = column.getTypeName();
                    }
                }
            }
        }
        RunValue rv = run.addValues(compare, column, value, split);
        if (null != column) {
            //value = convert(runtime, column, rv); //统一调用
        }
    }

    @Override
    public boolean convert(DataRuntime runtime, ConfigStore configs, Run run) {
        boolean result = false;
        if (null != run) {
            result = convert(runtime, new Table<>(run.getTable()), run);
        }
        return result;
    }

    @Override
    public boolean convert(DataRuntime runtime, Table table, Run run) {
        boolean result = false;
        LinkedHashMap<String, Column> columns = table.getColumns();

        if (ConfigTable.IS_AUTO_CHECK_METADATA) {
            if (null == columns || columns.isEmpty()) {
                columns = columns(runtime, null, false, table, false);
            }
        }
        List<RunValue> values = run.getRunValues();
        if (null != values) {
            for (RunValue value : values) {
                if (ConfigTable.IS_AUTO_CHECK_METADATA) {
                    result = convert(runtime, columns, value);
                } else {
                    result = convert(runtime, (Column) null, value);
                }
            }
        }
        return result;
    }

    @Override
    public boolean convert(DataRuntime runtime, Map<String, Column> columns, RunValue value) {
        boolean result = false;
        if (null != columns && null != value) {
            String key = value.getKey();
            if (null != key) {
                Column meta = columns.get(key.toUpperCase());
                result = convert(runtime, meta, value);
            }
        }
        return result;
    }

    /**
     * 根据数据库列属性 类型转换(一般是在更新数据库时调用)
     * 子类先解析(有些同名的类型以子类为准)、失败后再到这里解析
     *
     * @param metadata 列
     * @param run      最终待执行的命令和参数(如果是JDBC环境就是SQL)Value
     * @return boolean 是否完成类型转换,决定下一步是否继续
     */
    @Override
    public boolean convert(DataRuntime runtime, Column metadata, RunValue run) {
        if (null == run) {
            return true;
        }
        Object value = run.getValue();
        if (null == value) {
            return true;
        }
        try {
            if (null != metadata) {
                //根据列属性转换(最终也是根据java类型转换)
                value = convert(runtime, metadata, value);
            } else {
                DataWriter writer = writer(value.getClass());
                if (null != writer) {
                    value = writer.write(value, true);
                }
            }
            run.setValue(value);

        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public Object convert(DataRuntime runtime, Column metadata, Object value) {
        if (null == value) {
            return value;
        }
        try {
            if (null != metadata) {
                ColumnType columnType = metadata.getColumnType();
                if (null == columnType) {
                    columnType = type(metadata.getTypeName());
                    if (null != columnType) {
                        columnType.setArray(metadata.isArray());
                        metadata.setColumnType(columnType);
                    }
                }
//                value = convert(runtime, columnType, value);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return value;
    }


    @Override
    public String objectName(DataRuntime runtime, String name) {
        KeyAdapter.KEY_CASE keyCase = type().nameCase();
        if (null != keyCase) {
            return keyCase.convert(name);
        }
        return name;
    }


    protected String random(DataRuntime runtime) {
        StringBuilder builder = new StringBuilder();
        builder.append("[SQL:").append(System.currentTimeMillis()).append("-").append(BasicUtil.getRandomNumberString(8)).append("][thread:").append(Thread.currentThread().getId()).append("][ds:").append(runtime.datasource()).append("]");
        return builder.toString();
    }

    //A.ID,A.COOE,A.NAME
    protected String concat(String prefix, String split, List<String> columns) {
        StringBuilder builder = new StringBuilder();
        if (BasicUtil.isEmpty(prefix)) {
            prefix = "";
        } else {
            if (!prefix.endsWith(".")) {
                prefix += ".";
            }
        }

        boolean first = true;
        for (String column : columns) {
            if (!first) {
                builder.append(split);
            }
            first = false;
            builder.append(prefix).append(column);
        }
        return builder.toString();
    }

    //master.column = data.column
    protected String concatEqual(String master, String data, String split, List<String> columns) {
        StringBuilder builder = new StringBuilder();
        if (BasicUtil.isEmpty(master)) {
            master = "";
        } else {
            if (!master.endsWith(".")) {
                master += ".";
            }
        }
        if (BasicUtil.isEmpty(data)) {
            data = "";
        } else {
            if (!data.endsWith(".")) {
                data += ".";
            }
        }

        boolean first = true;
        for (String column : columns) {
            if (!first) {
                builder.append(split);
            }
            first = false;
            builder.append(master).append(column).append(" = ").append(data).append(column);
        }
        return builder.toString();
    }

    public StringBuilder delimiter(StringBuilder builder, String src) {
        return SQLUtil.delimiter(builder, src, getDelimiterFr(), getDelimiterTo());
    }

    public StringBuilder delimiter(StringBuilder builder, BaseMetadata src) {
        if (null != src) {
            String name = src.getName();
            if (BasicUtil.isNotEmpty(name)) {
                SQLUtil.delimiter(builder, name, getDelimiterFr(), getDelimiterTo());
            }
        }
        return builder;
    }

    public <T extends BaseMetadata> T search(List<T> list, String catalog, String schema, String name) {
        return BaseMetadata.search(list, catalog, schema, name);
    }

    public <T extends BaseMetadata> T search(List<T> list, String catalog, String name) {
        return BaseMetadata.search(list, catalog, name);
    }

    public <T extends BaseMetadata> T search(List<T> list, String name) {
        return BaseMetadata.search(list, name);
    }


    /**
     * 是否输出SQL日志
     *
     * @param configs ConfigStore
     * @return boolean
     */
    protected boolean IS_LOG_SQL(ConfigStore configs) {
        if (null != configs) {
            return configs.IS_LOG_SQL();
        }
        return ConfigTable.IS_LOG_SQL;
    }

    /**
     * insert update 时是否自动检测表结构(删除表中不存在的属性)
     *
     * @param configs ConfigStore
     * @return boolean
     */
    protected boolean IS_AUTO_CHECK_METADATA(ConfigStore configs) {
        if (null != configs) {
            return configs.IS_AUTO_CHECK_METADATA();
        }
        return ConfigTable.IS_AUTO_CHECK_METADATA;
    }

    /**
     * 查询返回空DataSet时，是否检测元数据信息
     *
     * @param configs ConfigStore
     * @return boolean
     */
    protected boolean IS_CHECK_EMPTY_SET_METADATA(ConfigStore configs) {
        if (null != configs) {
            return configs.IS_CHECK_EMPTY_SET_METADATA();
        }
        return ConfigTable.IS_CHECK_EMPTY_SET_METADATA;
    }

    /**
     * 是否输出慢SQL日志
     *
     * @param configs ConfigStore
     * @return boolean
     */
    protected boolean IS_LOG_SLOW_SQL(ConfigStore configs) {
        if (null != configs) {
            return configs.IS_LOG_SLOW_SQL();
        }
        return ConfigTable.IS_LOG_SLOW_SQL;
    }


    /**
     * 异常时是否输出SQL日志
     *
     * @param configs ConfigStore
     * @return boolean
     */
    protected boolean IS_LOG_SQL_WHEN_ERROR(ConfigStore configs) {
        if (null != configs) {
            return configs.IS_LOG_SQL_WHEN_ERROR();
        }
        return ConfigTable.IS_LOG_SQL_WHEN_ERROR;
    }

    /**
     * 是否输出异常堆栈日志
     *
     * @param configs ConfigStore
     * @return boolean
     */
    protected boolean IS_PRINT_EXCEPTION_STACK_TRACE(ConfigStore configs) {
        if (null != configs) {
            return configs.IS_PRINT_EXCEPTION_STACK_TRACE();
        }
        return ConfigTable.IS_PRINT_EXCEPTION_STACK_TRACE;
    }

    protected boolean IS_SQL_LOG_PLACEHOLDER(ConfigStore configs) {
        if (null != configs) {
            return configs.IS_SQL_LOG_PLACEHOLDER();
        }
        return ConfigTable.IS_SQL_LOG_PLACEHOLDER;
    }

    /**
     * 是否显示SQL耗时
     *
     * @param configs ConfigStore
     * @return boolean
     */
    protected boolean IS_LOG_SQL_TIME(ConfigStore configs) {
        if (null != configs) {
            return configs.IS_LOG_SQL_TIME();
        }
        return ConfigTable.IS_LOG_SQL_TIME;
    }

    /**
     * 慢SQL判断标准
     *
     * @param configs ConfigStore
     * @return long
     */
    protected long SLOW_SQL_MILLIS(ConfigStore configs) {
        if (null != configs) {
            return configs.SLOW_SQL_MILLIS();
        }
        return ConfigTable.SLOW_SQL_MILLIS;
    }

    /**
     * 是否抛出查询异常
     *
     * @param configs ConfigStore
     * @return boolean
     */
    protected boolean IS_THROW_SQL_QUERY_EXCEPTION(ConfigStore configs) {
        if (null != configs) {
            return configs.IS_THROW_SQL_QUERY_EXCEPTION();
        }
        return ConfigTable.IS_THROW_SQL_QUERY_EXCEPTION;
    }

    protected boolean IS_LOG_SQL_WARN(ConfigStore configs) {
        if (null != configs) {
            return configs.IS_LOG_SQL_WARN();
        }
        return ConfigTable.IS_LOG_SQL_WARN;
    }

    protected <T extends BaseMetadata> void fillSchema(T source, T target) {
        Catalog catalog = source.getCatalog();
        Schema schema = source.getSchema();
        if (BasicUtil.isNotEmpty(catalog)) {
            target.setCatalog(catalog);
        }
        if (BasicUtil.isNotEmpty(schema)) {
            target.setSchema(schema);
        }
    }

}