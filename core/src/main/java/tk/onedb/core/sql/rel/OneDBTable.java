package tk.onedb.core.sql.rel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.protobuf.TextFormat;
import com.google.protobuf.TextFormat.ParseException;

import org.apache.calcite.adapter.java.AbstractQueryableTable;
import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.linq4j.Queryable;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelOptTable.ToRelContext;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeImpl;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.rel.type.RelProtoDataType;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.TranslatableTable;
import org.apache.calcite.schema.impl.AbstractTableQueryable;
import org.apache.calcite.sql.type.SqlTypeFactoryImpl;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tk.onedb.core.client.DBClient;
import tk.onedb.core.data.Header;
import tk.onedb.core.data.TableInfo;
import tk.onedb.core.data.TypeConverter;
import tk.onedb.core.sql.enumerator.OneDBEnumerator;
import tk.onedb.core.sql.schema.OneDBSchema;
import tk.onedb.core.table.OneDBTableInfo;
import tk.onedb.core.table.TableMeta;
import tk.onedb.rpc.OneDBCommon.OneDBQueryProto;

public class OneDBTable extends AbstractQueryableTable implements TranslatableTable {
  private static final Logger LOG = LoggerFactory.getLogger(OneDBTable.class);

  final OneDBSchema schema;
  final RelProtoDataType protoRowType;
  final OneDBTableInfo tableInfo;

  OneDBTable(String tableName, OneDBSchema schema, Header header, RelProtoDataType protoRowType) {
    super(Object[].class);
    this.schema = schema;
    this.protoRowType = protoRowType;
    this.tableInfo = new OneDBTableInfo(tableName, header);
  }

  @Override
  public RelDataType getRowType(RelDataTypeFactory typeFactory) {
    return protoRowType.apply(typeFactory);
  }

  static Table create(OneDBSchema schema, String tableName, Header header, RelProtoDataType protoRowType) {
    return new OneDBTable(tableName, schema, header, protoRowType);
  }

  public static Table create(OneDBSchema schema, TableMeta tableMeta) {
    final String tableName = tableMeta.tableName;
    OneDBTable table = null;
    List<Pair<DBClient, TableInfo>> localInfos = new ArrayList<>();
    for (TableMeta.FedMeta fedMeta : tableMeta.feds) {
      DBClient client = schema.getDBClient(fedMeta.endpoint);
      if (client == null) {
        client = schema.addDB(fedMeta.endpoint);
      }
      Header header = client.getTableHeader(fedMeta.localName);
      LOG.info("Table {} header {} from {}", fedMeta.localName, header.toString(), fedMeta.endpoint);
      localInfos.add(Pair.of(client, TableInfo.of(fedMeta.localName, header)));
    }
    if (localInfos.size() > 0) {
      TableInfo standard = localInfos.get(0).getValue();
      for (Pair<DBClient, TableInfo> pair : localInfos) {
        if (!standard.getHeader().equals(pair.getValue().getHeader())) {
          LOG.warn("Header of {} {} {} mismatch with {} {} {}", localInfos.get(0).getKey(), localInfos.get(0).getValue().getName(), standard.toString(), pair.getKey(), standard.getName(), standard.getHeader());
          return null;
        }
      }
      RelProtoDataType dataType = getRelDataType(standard.getHeader());
      table = new OneDBTable(tableName, schema, standard.getHeader(), dataType);
      for (Pair<DBClient, TableInfo> pair : localInfos) {
        table.addDB(pair.getKey(), pair.getValue().getName());
      }
      schema.addTable(tableMeta.tableName, table);
    }
    if (table == null) {
      LOG.error("Fail to init table {}", tableMeta.tableName);
    }
    return table;
  }

  public static Table create(OneDBSchema schema, String tableName, Map operand, RelProtoDataType protoRowType) {
    List<LinkedHashMap<String, Object>> feds = (List<LinkedHashMap<String, Object>>) operand.get("feds");
    OneDBTable table = null;
    for (LinkedHashMap<String, Object> fed : feds) {
      String endpoint = fed.get("endpoint").toString();
      String localName = fed.get("name").toString();
      DBClient client = schema.getDBClient(endpoint);
      if (client == null) {
        throw new RuntimeException("endpoint not exist");
      }
      Header header = client.getTableHeader(localName);
      LOG.info("{}: header {} from [{} : {}]", tableName, header.toString(), endpoint, localName);
      if (table == null) {
        RelProtoDataType dataType = getRelDataType(client, localName);
        table = new OneDBTable(tableName, schema, header, dataType);
        table.addDB(client, localName);
        schema.addTable(tableName, table);
      } else {
        if (table.getHeader().equals(header)) {
          table.addDB(client, localName);
        } else {
          LOG.warn("header in {} mismatch", endpoint);
        }
      }
    }
    if (table == null) {
      LOG.error("Fail to init table {}", tableName);
    }
    return table;
  }

  public static RelProtoDataType getRelDataType(DBClient client, String localName) {
    Header header = client.getTableHeader(localName);
    return getRelDataType(header);
  }

  public static RelProtoDataType getRelDataType(Header header) {
    final RelDataTypeFactory typeFactory = new SqlTypeFactoryImpl(RelDataTypeSystem.DEFAULT);
    final RelDataTypeFactory.Builder fieldInfo = typeFactory.builder();
    for (int i = 0; i < header.size(); ++i) {
      final SqlTypeName typeName = TypeConverter.convert2SqlType(header.getType(i));
      fieldInfo.add(header.getName(i), typeName).nullable(true);
    }
    return RelDataTypeImpl.proto(fieldInfo.build());
  }

  public void addDB(DBClient client, String localName) {
    tableInfo.addLocalTable(client, localName);
  }

  public static class OneDBQueryable<T> extends AbstractTableQueryable<T> {
    public OneDBQueryable(QueryProvider queryProvider, SchemaPlus schema, OneDBTable table, String tableName) {
      super(queryProvider, schema, table, tableName);
    }

    private OneDBTable getTable() {
      return (OneDBTable) table;
    }

    @Override
    public Enumerator<T> enumerator() {
      final Enumerable<T> enumerable = (Enumerable<T>) getTable().query();
      return enumerable.enumerator();
    }

    public Enumerable<Object> query(String queryStr) {
      OneDBQueryProto.Builder builder = OneDBQueryProto.newBuilder();
      try {
        TextFormat.getParser().merge(queryStr, builder);
      } catch (ParseException e) {
        e.printStackTrace();
      }
      return getTable().query(builder.build());
    }
  }

  public Enumerable<Object> query() {
    System.out.println("Should not use this method");
    return query(null);
  }

  public Enumerable<Object> query(OneDBQueryProto query) {
    return new AbstractEnumerable<Object>() {
      Enumerator<Object> enumerator;

      @Override
      public Enumerator<Object> enumerator() {
        if (enumerator == null) {
          this.enumerator = new OneDBEnumerator(getTableName(), schema, query);
        } else {
          this.enumerator.reset();
        }
        return this.enumerator;
      }
    };
  }

  @Override
  public <T> Queryable<T> asQueryable(QueryProvider queryProvider, SchemaPlus schema, String tableName) {
    return new OneDBQueryable<>(queryProvider, schema, this, tableName);
  }

  @Override
  public RelNode toRel(ToRelContext context, RelOptTable relOptTable) {
    final RelOptCluster cluster = context.getCluster();
    return new OneDBTableScan(cluster, cluster.traitSetOf(OneDBRel.CONVENTION), relOptTable, this, null);
  }

  public OneDBTableInfo getTableInfo() {
    return tableInfo;
  }

  public Header getHeader() {
    return tableInfo.getHeader();
  }

  public String getTableName() {
    return tableInfo.getName();
  }
}