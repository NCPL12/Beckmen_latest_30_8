package ncpl.bms.reports.service;
import lombok.extern.slf4j.Slf4j;
import ncpl.bms.reports.db.info.TableInfoService;
import ncpl.bms.reports.model.dao.ReportTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.cache.annotation.Cacheable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ReportDataService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TableInfoService tableInfoService;

    @Autowired
    private ReportTemplateService templateService;

    /**
     * APPROACH 1: Direct Query with Dynamic JOIN (Recommended)
     * No intermediate table, query source tables directly
     */
    public List<Map<String, Object>> generateReportData(Long templateId, String fromDateMillis, String toDateMillis) {
        Timestamp fromDate = new Timestamp(Long.parseLong(fromDateMillis));
        Timestamp toDate = new Timestamp(Long.parseLong(toDateMillis));

        log.info("Generating report data directly from source tables for template: {}", templateId);

        ReportTemplate template = templateService.getById(templateId);
        List<String> requiredTables = getRequiredTables(template);

        // Use Union approach which is more reliable for SQL Server
        List<Map<String, Object>> result = queryWithUnionAndPivot(requiredTables, fromDate, toDate);

        // Round all numeric values in the result
        return roundValuesInResult(result);
    }

    /**
     * Simpler approach - Query each table separately and combine in Java
     * This is more reliable and easier to debug
     */
    public List<Map<String, Object>> generateReportDataSimple(Long templateId, String fromDateMillis, String toDateMillis) {
        Timestamp fromDate = new Timestamp(Long.parseLong(fromDateMillis));
        Timestamp toDate = new Timestamp(Long.parseLong(toDateMillis));

        log.info("Generating report data using simple approach for template: {}", templateId);

        ReportTemplate template = templateService.getById(templateId);
        List<String> requiredTables = getRequiredTables(template);

        // Get all unique 10-minute intervals
        Set<Timestamp> allIntervals = new TreeSet<>();
        Map<String, Map<Timestamp, Object>> tableData = new HashMap<>();

        // Query each table separately
        for (String table : requiredTables) {
            String sql = "SELECT DATEADD(MINUTE, (DATEDIFF(MINUTE, '1900-01-01', timestamp) / 10) * 10, '1900-01-01') as normalized_timestamp, " +
                    "value FROM " + table + " WHERE timestamp BETWEEN ? AND ?";

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, fromDate, toDate);

            Map<Timestamp, Object> timestampValueMap = new HashMap<>();
            for (Map<String, Object> row : rows) {
                Timestamp normalizedTime = (Timestamp) row.get("normalized_timestamp");
                Object value = row.get("value");

                // Round the value before storing
                Object roundedValue = roundValue(value);

                allIntervals.add(normalizedTime);
                timestampValueMap.put(normalizedTime, roundedValue);
            }

            tableData.put(table, timestampValueMap);
        }

        // Combine all data
        List<Map<String, Object>> result = new ArrayList<>();
        for (Timestamp interval : allIntervals) {
            Map<String, Object> row = new HashMap<>();
            row.put("normalized_timestamp", interval);

            for (String table : requiredTables) {
                Object value = tableData.get(table).get(interval);
                row.put(table, value);
            }

            result.add(row);
        }

        return result;
    }

    /**
     * Query all source tables directly with a single SQL using dynamic JOINs
     */
    private List<Map<String, Object>> querySourceTablesDirectly(List<String> tables, Timestamp fromDate, Timestamp toDate) {
        if (tables.isEmpty()) {
            return Collections.emptyList();
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        sql.append("DATEADD(MINUTE, (DATEDIFF(MINUTE, '1900-01-01', t1.timestamp) / 10) * 10, '1900-01-01') as normalized_timestamp");

        // Add columns for each table with rounding in SQL
        for (int i = 0; i < tables.size(); i++) {
            sql.append(", ROUND(t").append(i + 1).append(".value, 1) as ").append(tables.get(i));
        }

        // FROM clause with first table
        sql.append(" FROM ").append(tables.get(0)).append(" t1");

        // LEFT JOIN with other tables on normalized timestamp
        for (int i = 1; i < tables.size(); i++) {
            sql.append(" LEFT JOIN ").append(tables.get(i)).append(" t").append(i + 1);
            sql.append(" ON DATEADD(MINUTE, (DATEDIFF(MINUTE, '1900-01-01', t1.timestamp) / 10) * 10, '1900-01-01') = ");
            sql.append("DATEADD(MINUTE, (DATEDIFF(MINUTE, '1900-01-01', t").append(i + 1).append(".timestamp) / 10) * 10, '1900-01-01')");
        }

        sql.append(" WHERE t1.timestamp BETWEEN ? AND ?");
        sql.append(" GROUP BY DATEADD(MINUTE, (DATEDIFF(MINUTE, '1900-01-01', t1.timestamp) / 10) * 10, '1900-01-01')");
        sql.append(" ORDER BY normalized_timestamp");

        log.info("Executing direct query: {}", sql.toString());
        List<Map<String, Object>> result = jdbcTemplate.queryForList(sql.toString(), fromDate, toDate);

        // Additional Java-side rounding as backup
        return roundValuesInResult(result);
    }

    /**
     *  Materialized View Approach (Best for frequent queries)
     */
    public void createMaterializedView(List<String> tables) {
        String viewName = "report_data_view";

        // Drop existing view (SQL Server syntax)
        try {
            jdbcTemplate.execute("DROP VIEW IF EXISTS " + viewName);
        } catch (Exception e) {
            log.debug("View didn't exist: {}", e.getMessage());
        }

        StringBuilder createViewSql = new StringBuilder();
        createViewSql.append("CREATE VIEW ").append(viewName).append(" AS SELECT ");
        createViewSql.append("DATEADD(MINUTE, (DATEDIFF(MINUTE, '1900-01-01', t1.timestamp) / 10) * 10, '1900-01-01') as timestamp");

        // Add rounded values in the view
        for (int i = 0; i < tables.size(); i++) {
            createViewSql.append(", ROUND(t").append(i + 1).append(".value, 1) as ").append(tables.get(i));
        }

        createViewSql.append(" FROM ").append(tables.get(0)).append(" t1");

        for (int i = 1; i < tables.size(); i++) {
            createViewSql.append(" LEFT JOIN ").append(tables.get(i)).append(" t").append(i + 1);
            createViewSql.append(" ON DATEADD(MINUTE, (DATEDIFF(MINUTE, '1900-01-01', t1.timestamp) / 10) * 10, '1900-01-01') = ");
            createViewSql.append("DATEADD(MINUTE, (DATEDIFF(MINUTE, '1900-01-01', t").append(i + 1).append(".timestamp) / 10) * 10, '1900-01-01')");
        }

        jdbcTemplate.execute(createViewSql.toString());
        log.info("Created materialized view: {}", viewName);
    }

    @Cacheable(value = "reportData", key = "#templateId + '_' + #fromDate + '_' + #toDate")
    public List<Map<String, Object>> getReportDataFromView(Long templateId, String fromDate, String toDate) {
        ReportTemplate template = templateService.getById(templateId);
        List<String> requiredColumns = getRequiredColumns(template);

        StringBuilder sql = new StringBuilder("SELECT timestamp");
        for (String column : requiredColumns) {
            sql.append(", ").append(column);
        }
        sql.append(" FROM report_data_view WHERE timestamp BETWEEN ? AND ? ORDER BY timestamp");

        List<Map<String, Object>> result = jdbcTemplate.queryForList(sql.toString(), Timestamp.valueOf(fromDate), Timestamp.valueOf(toDate));

        // Round values as additional safety measure
        return roundValuesInResult(result);
    }

    /**
     * APPROACH 3: Union-based query (When JOIN is not suitable)
     */
    public List<Map<String, Object>> generateReportDataWithUnion(Long templateId, String fromDateMillis, String toDateMillis) {
        Timestamp fromDate = new Timestamp(Long.parseLong(fromDateMillis));
        Timestamp toDate = new Timestamp(Long.parseLong(toDateMillis));

        ReportTemplate template = templateService.getById(templateId);
        List<String> requiredTables = getRequiredTables(template);

        List<Map<String, Object>> result = queryWithUnionAndPivot(requiredTables, fromDate, toDate);
        return roundValuesInResult(result);
    }

    private List<Map<String, Object>> queryWithUnionAndPivot(List<String> tables, Timestamp fromDate, Timestamp toDate) {
        if (tables.isEmpty()) {
            return Collections.emptyList();
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT timestamp");

        // Add ROUND function for each table column
        for (String table : tables) {
            sql.append(", ROUND(MAX(CASE WHEN source_table = '").append(table).append("' THEN value END), 1) as ").append(table);
        }

        sql.append(" FROM (");

        // UNION all tables
        boolean first = true;
        for (String table : tables) {
            if (!first) sql.append(" UNION ALL ");
            sql.append("SELECT '").append(table).append("' as source_table, ");
            sql.append("DATEADD(MINUTE, (DATEDIFF(MINUTE, '1900-01-01', timestamp) / 10) * 10, '1900-01-01') as timestamp, ");
            sql.append("value FROM ").append(table);
            sql.append(" WHERE timestamp BETWEEN ? AND ?");
            first = false;
        }

        sql.append(") unified_data GROUP BY timestamp ORDER BY timestamp");

        // Prepare parameters (fromDate and toDate for each table)
        Object[] params = new Object[tables.size() * 2];
        for (int i = 0; i < tables.size(); i++) {
            params[i * 2] = fromDate;
            params[i * 2 + 1] = toDate;
        }

        List<Map<String, Object>> result = jdbcTemplate.queryForList(sql.toString(), params);
        return roundValuesInResult(result);
    }


    public void generateReportDataStream(Long templateId, String fromDateMillis, String toDateMillis,
                                         ReportDataCallback callback) {
        Timestamp fromDate = new Timestamp(Long.parseLong(fromDateMillis));
        Timestamp toDate = new Timestamp(Long.parseLong(toDateMillis));

        ReportTemplate template = templateService.getById(templateId);
        List<String> requiredTables = getRequiredTables(template);

        // Process in batches to avoid memory issues
        int batchSize = 1000;
        int offset = 0;

        while (true) {
            List<Map<String, Object>> batch = queryBatch(requiredTables, fromDate, toDate, offset, batchSize);
            if (batch.isEmpty()) break;

            // Round values in each batch
            List<Map<String, Object>> roundedBatch = roundValuesInResult(batch);
            callback.processBatch(roundedBatch);
            offset += batchSize;
        }
    }

    private List<Map<String, Object>> queryBatch(List<String> tables, Timestamp fromDate, Timestamp toDate,
                                                 int offset, int limit) {
        if (tables.isEmpty()) {
            return Collections.emptyList();
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        sql.append("DATEADD(MINUTE, (DATEDIFF(MINUTE, '1900-01-01', t1.timestamp) / 10) * 10, '1900-01-01') as normalized_timestamp");

        // Add columns for each table with rounding
        for (int i = 0; i < tables.size(); i++) {
            sql.append(", ROUND(t").append(i + 1).append(".value, 1) as ").append(tables.get(i));
        }

        // FROM clause with first table
        sql.append(" FROM ").append(tables.get(0)).append(" t1");

        // LEFT JOIN with other tables on normalized timestamp
        for (int i = 1; i < tables.size(); i++) {
            sql.append(" LEFT JOIN ").append(tables.get(i)).append(" t").append(i + 1);
            sql.append(" ON DATEADD(MINUTE, (DATEDIFF(MINUTE, '1900-01-01', t1.timestamp) / 10) * 10, '1900-01-01') = ");
            sql.append("DATEADD(MINUTE, (DATEDIFF(MINUTE, '1900-01-01', t").append(i + 1).append(".timestamp) / 10) * 10, '1900-01-01')");
        }

        sql.append(" WHERE t1.timestamp BETWEEN ? AND ?");
        sql.append(" GROUP BY DATEADD(MINUTE, (DATEDIFF(MINUTE, '1900-01-01', t1.timestamp) / 10) * 10, '1900-01-01')");
        sql.append(" ORDER BY normalized_timestamp");
        sql.append(" OFFSET ").append(offset).append(" ROWS FETCH NEXT ").append(limit).append(" ROWS ONLY");

        return jdbcTemplate.queryForList(sql.toString(), fromDate, toDate);
    }

    /**
     * Optimized statistics calculation - direct from source tables
     */
    public Map<String, Map<String, Double>> calculateStatistics(Long templateId, String fromDate, String toDate) {
        ReportTemplate template = templateService.getById(templateId);
        List<String> requiredTables = getRequiredTables(template);
        Map<String, Map<String, Double>> statistics = new LinkedHashMap<>();

        for (String table : requiredTables) {
            String sql = "SELECT ROUND(MAX(value), 1) as max_val, ROUND(MIN(value), 1) as min_val, ROUND(AVG(value), 1) as avg_val " +
                    "FROM " + table + " WHERE timestamp BETWEEN ? AND ?";

            Map<String, Object> result = jdbcTemplate.queryForMap(sql,
                    Timestamp.valueOf(fromDate), Timestamp.valueOf(toDate));

            Map<String, Double> statMap = new HashMap<>();
            statMap.put("max", convertToDouble(result.get("max_val")));
            statMap.put("min", convertToDouble(result.get("min_val")));
            statMap.put("avg", convertToDouble(result.get("avg_val")));
            statistics.put(table, statMap);
        }

        return statistics;
    }

    // Helper methods for rounding

    /**
     * Round a single value to 1 decimal place
     * Example: 34.56666 -> 34.5
     */
    private Object roundValue(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Number) {
            Number numValue = (Number) value;
            BigDecimal bd = new BigDecimal(numValue.toString());
            bd = bd.setScale(1, RoundingMode.HALF_UP);
            return bd.doubleValue();
        }

        // If it's a string that represents a number, try to parse and round it
        if (value instanceof String) {
            try {
                BigDecimal bd = new BigDecimal((String) value);
                bd = bd.setScale(1, RoundingMode.HALF_UP);
                return bd.doubleValue();
            } catch (NumberFormatException e) {
                // If it's not a number, return as is
                return value;
            }
        }

        return value;
    }

    /**
     * Round all numeric values in a result set
     */
    private List<Map<String, Object>> roundValuesInResult(List<Map<String, Object>> result) {
        if (result == null || result.isEmpty()) {
            return result;
        }

        for (Map<String, Object> row : result) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                // Skip timestamp columns
                if (key.toLowerCase().contains("timestamp") || key.toLowerCase().contains("time")) {
                    continue;
                }

                // Round numeric values
                Object roundedValue = roundValue(value);
                row.put(key, roundedValue);
            }
        }

        return result;
    }

    // Other helper methods
    private List<String> getRequiredTables(ReportTemplate template) {
        return template.getParameters().stream()
                .map(this::removeSuffix)
                .distinct()
                .collect(Collectors.toList());
    }

    private List<String> getRequiredColumns(ReportTemplate template) {
        return template.getParameters().stream()
                .map(this::removeSuffix)
                .distinct()
                .collect(Collectors.toList());
    }

    private String removeSuffix(String columnName) {
        String base = columnName;
        if (base.contains("_From_")) base = base.substring(0, base.indexOf("_From_"));
        if (base.contains("_To_")) base = base.substring(0, base.indexOf("_To_"));
        if (base.contains("_Unit_")) base = base.substring(0, base.indexOf("_Unit_"));
        return base;
    }

    private Integer convertToInteger(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }

    private Double convertToDouble(Object value) {
        if (value instanceof Number) {
            Number numValue = (Number) value;
            BigDecimal bd = new BigDecimal(numValue.toString());
            bd = bd.setScale(1, RoundingMode.HALF_UP);
            return bd.doubleValue();
        }
        return null;
    }

    // Interface for streaming callback
    public interface ReportDataCallback {
        void processBatch(List<Map<String, Object>> batch);
    }
}