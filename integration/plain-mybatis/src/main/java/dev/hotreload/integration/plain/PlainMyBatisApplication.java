package dev.hotreload.integration.plain;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.defaults.DefaultSqlSessionFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

public final class PlainMyBatisApplication {
    private static final String OUTPUT_PREFIX = "HOTRELOAD_FIXTURE ";
    private static final String RESOURCE = "mappers/ProbeMapper.xml";
    private static final String STATEMENT = "probe.Mapper.value";

    private final SqlSessionFactory sqlSessionFactory;
    private final ReloadableService reloadableService = new ReloadableService();
    private final PrintWriter output = new PrintWriter(
            new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);

    private PlainMyBatisApplication(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
    }

    public static void main(String[] args) throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream mapper = Resources.getResourceAsStream(RESOURCE)) {
            new XMLMapperBuilder(mapper, configuration, RESOURCE, configuration.getSqlFragments()).parse();
        }
        new PlainMyBatisApplication(new DefaultSqlSessionFactory(configuration)).run();
    }

    private void run() throws Exception {
        emit("READY");
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String command;
        while ((command = input.readLine()) != null) {
            if ("CLASS".equals(command)) {
                emit("CLASS=" + reloadableService.value());
            } else if ("XML".equals(command)) {
                String sql = sqlSessionFactory.getConfiguration().getMappedStatement(STATEMENT)
                        .getBoundSql(null).getSql().replaceAll("\\s+", " ").trim();
                emit("XML=" + sql);
            } else if ("THREADS".equals(command)) {
                emit("THREADS=" + ownedThreadCount());
            } else if ("CONFIGURATIONS".equals(command)) {
                emit("CONFIGURATIONS=" + trackedConfigurationCount());
            } else if ("STOP".equals(command)) {
                emit("STOPPED");
                return;
            } else {
                emit("ERROR=unknown_command");
            }
        }
    }

    private void emit(String message) {
        output.println(OUTPUT_PREFIX + message);
    }

    private static int ownedThreadCount() {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith("hotreload-")) count++;
        }
        return count;
    }

    private static int trackedConfigurationCount() throws Exception {
        Class<?> bridge = Class.forName("dev.hotreload.bootstrap.HotReloadBridge", true, null);
        java.util.List<?> configurations = (java.util.List<?>) bridge
                .getMethod("snapshotConfigurations").invoke(null);
        return configurations.size();
    }
}
