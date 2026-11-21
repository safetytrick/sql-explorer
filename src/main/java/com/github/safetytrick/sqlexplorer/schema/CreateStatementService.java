package com.github.safetytrick.sqlexplorer.schema;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import com.github.safetytrick.sqlexplorer.analysis.IgnoredSchemas;
import org.hibernate.engine.jdbc.internal.BasicFormatterImpl;
import org.hibernate.engine.jdbc.internal.Formatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CreateStatementService {

    private static final Logger log = LoggerFactory.getLogger(CreateStatementService.class);

    private final IgnoredSchemas ignoredSchemas;
    private final Formatter sqlFormatter = new BasicFormatterImpl();

    public CreateStatementService(IgnoredSchemas ignoredSchemas) {
        this.ignoredSchemas = ignoredSchemas;
    }

    public String loadCreateStatement(DataSource dataSource, TableStructure table) {
        if (ignoredSchemas.contains(table.schema())) {
            return "system table";
        }
        log.debug("Reading create statement for {}.{}", table.schema(), table.name());
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                 "show create table `" + table.schema() + "`.`" + table.name() + "`")) {
            if (!resultSet.next()) {
                return "";
            }
            String createStatement = resultSet.getString(2);
            return sqlFormatter.format(createStatement);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
