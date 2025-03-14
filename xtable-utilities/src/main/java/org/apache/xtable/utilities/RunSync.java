/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
 
package org.apache.xtable.utilities;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Collectors;

import lombok.Data;
import lombok.extern.log4j.Log4j2;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.hadoop.conf.Configuration;

import com.fasterxml.jackson.annotation.JsonMerge;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.annotations.VisibleForTesting;

import org.apache.xtable.conversion.ConversionConfig;
import org.apache.xtable.conversion.ConversionController;
import org.apache.xtable.conversion.ConversionSourceProvider;
import org.apache.xtable.conversion.SourceTable;
import org.apache.xtable.conversion.TargetTable;
import org.apache.xtable.hudi.HudiSourceConfig;
import org.apache.xtable.iceberg.IcebergCatalogConfig;
import org.apache.xtable.model.storage.TableFormat;
import org.apache.xtable.model.sync.SyncMode;
import org.apache.xtable.reflection.ReflectionUtils;

/**
 * Provides a standalone runner for the sync process. See README.md for more details on how to run
 * this.
 */
@Log4j2
public class RunSync {

  public static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
  private static final String DATASET_CONFIG_OPTION = "d";
  private static final String HADOOP_CONFIG_PATH = "p";
  private static final String CONVERTERS_CONFIG_PATH = "c";
  private static final String ICEBERG_CATALOG_CONFIG_PATH = "i";
  private static final String HELP_OPTION = "h";

  private static final Options OPTIONS =
      new Options()
          .addRequiredOption(
              DATASET_CONFIG_OPTION,
              "datasetConfig",
              true,
              "The path to a yaml file containing dataset configuration")
          .addOption(
              HADOOP_CONFIG_PATH,
              "hadoopConfig",
              true,
              "Hadoop config xml file path containing configs necessary to access the "
                  + "file system. These configs will override the default configs.")
          .addOption(
              CONVERTERS_CONFIG_PATH,
              "convertersConfig",
              true,
              "The path to a yaml file containing InternalTable converter configurations. "
                  + "These configs will override the default")
          .addOption(
              ICEBERG_CATALOG_CONFIG_PATH,
              "icebergCatalogConfig",
              true,
              "The path to a yaml file containing Iceberg catalog configuration. The configuration will be "
                  + "used for any Iceberg source or target.")
          .addOption(HELP_OPTION, "help", false, "Displays help information to run this utility");

  static SourceTable sourceTableBuilder(
      DatasetConfig.Table table,
      IcebergCatalogConfig icebergCatalogConfig,
      DatasetConfig datasetConfig,
      Properties sourceProperties) {
    Objects.requireNonNull(table, "Table cannot be null");
    Objects.requireNonNull(datasetConfig, "datasetConfig cannot be null");
    SourceTable sourceTable =
        SourceTable.builder()
            .name(table.getTableName())
            .basePath(table.getTableBasePath())
            .namespace(table.getNamespace() == null ? null : table.getNamespace().split("\\."))
            .dataPath(table.getTableDataPath())
            .catalogConfig(icebergCatalogConfig)
            .additionalProperties(sourceProperties)
            .formatName(datasetConfig.sourceFormat)
            .build();
    return sourceTable;
  }

  static List<TargetTable> targetTableBuilder(
      DatasetConfig.Table table,
      IcebergCatalogConfig icebergCatalogConfig,
      List<String> tableFormatList) {
    Objects.requireNonNull(table, "Table cannot be null");
    Objects.requireNonNull(tableFormatList, "tableFormatList cannot be null");
    List<TargetTable> targetTables =
        tableFormatList.stream()
            .map(
                tableFormat ->
                    TargetTable.builder()
                        .name(table.getTableName())
                        .basePath(table.getTableBasePath())
                        .namespace(
                            table.getNamespace() == null ? null : table.getNamespace().split("\\."))
                        .catalogConfig(icebergCatalogConfig)
                        .formatName(tableFormat)
                        .build())
            .collect(Collectors.toList());
    return targetTables;
  }

  static void formatConvertor(
      DatasetConfig datasetConfig,
      List<String> tableFormatList,
      IcebergCatalogConfig icebergCatalogConfig,
      Configuration hadoopConf,
      ConversionSourceProvider conversionSourceProvider) {
    ConversionController conversionController = new ConversionController(hadoopConf);
    for (DatasetConfig.Table table : datasetConfig.getDatasets()) {
      log.info(
          "Running sync for basePath {} for following table formats {}",
          table.getTableBasePath(),
          tableFormatList);
      Properties sourceProperties = new Properties();
      if (table.getPartitionSpec() != null) {
        sourceProperties.put(
            HudiSourceConfig.PARTITION_FIELD_SPEC_CONFIG, table.getPartitionSpec());
      }

      SourceTable sourceTable =
          sourceTableBuilder(table, icebergCatalogConfig, datasetConfig, sourceProperties);
      List<TargetTable> targetTables =
          targetTableBuilder(table, icebergCatalogConfig, tableFormatList);
      ConversionConfig conversionConfig =
          ConversionConfig.builder()
              .sourceTable(sourceTable)
              .targetTables(targetTables)
              .syncMode(SyncMode.INCREMENTAL)
              .build();
      try {
        conversionController.sync(conversionConfig, conversionSourceProvider);
      } catch (Exception e) {
        log.error(String.format("Error running sync for %s", table.getTableBasePath()), e);
      }
    }
  }

  static DatasetConfig getDatasetConfig(String datasetConfigPath) throws IOException {
    // Initialize DatasetConfig
    DatasetConfig datasetConfig = new DatasetConfig();

    try (InputStream inputStream = Files.newInputStream(Paths.get(datasetConfigPath))) {
      ObjectReader objectReader = YAML_MAPPER.readerForUpdating(datasetConfig);
      objectReader.readValue(inputStream);
    }
    return datasetConfig;
  }

  static Configuration gethadoopConf(String hadoopConfigPath) throws IOException {
    // Load configurations
    byte[] customConfig = getCustomConfigurations(hadoopConfigPath);
    Configuration hadoopConf = loadHadoopConf(customConfig);
    return hadoopConf;
  }

  static IcebergCatalogConfig getIcebergCatalogConfig(String icebergCatalogConfigPath)
      throws IOException {
    // Load configurations
    byte[] icebergCatalogConfigInput = getCustomConfigurations(icebergCatalogConfigPath);
    IcebergCatalogConfig icebergCatalogConfig = loadIcebergCatalogConfig(icebergCatalogConfigInput);
    return icebergCatalogConfig;
  }

  static ConversionSourceProvider<?> getConversionSourceProvider(
      String conversionProviderConfigpath, DatasetConfig datasetConfig, Configuration hadoopConf)
      throws IOException {
    // Process source format
    String sourceFormat = datasetConfig.sourceFormat;
    byte[] customConfig = getCustomConfigurations(conversionProviderConfigpath);
    TableFormatConverters tableFormatConverters = loadTableFormatConversionConfigs(customConfig);
    TableFormatConverters.ConversionConfig sourceConversionConfig =
        tableFormatConverters.getTableFormatConverters().get(sourceFormat);
    if (sourceConversionConfig == null) {
      throw new IllegalArgumentException(
          String.format(
              "Source format %s is not supported. Known source and target formats are %s",
              sourceFormat, tableFormatConverters.getTableFormatConverters().keySet()));
    }
    String sourceProviderClass = sourceConversionConfig.conversionSourceProviderClass;
    ConversionSourceProvider<?> conversionSourceProvider =
        ReflectionUtils.createInstanceOfClass(sourceProviderClass);
    conversionSourceProvider.init(hadoopConf);
    return conversionSourceProvider;
  }

  static CommandLine commandParser(String[] args) {
    CommandLineParser parser = new DefaultParser();

    CommandLine cmd;
    try {
      cmd = parser.parse(OPTIONS, args);
    } catch (ParseException e) {
      new HelpFormatter().printHelp("xtable.jar", OPTIONS, true);
      return null;
    }

    if (cmd.hasOption(HELP_OPTION)) {
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp("RunSync", OPTIONS);
      return null;
    }
    return cmd;
  }

  static String getValueFromConfig(CommandLine cmd, String configFlag) {
    return cmd.getOptionValue(configFlag);
  }

  public static void main(String[] args) throws IOException {
    CommandLine cmd = commandParser(args);
    String datasetConfigpath = getValueFromConfig(cmd, DATASET_CONFIG_OPTION);
    String icebergCatalogConfigpath = getValueFromConfig(cmd, ICEBERG_CATALOG_CONFIG_PATH);
    String hadoopConfigpath = getValueFromConfig(cmd, HADOOP_CONFIG_PATH);
    String conversionProviderConfigpath = getValueFromConfig(cmd, CONVERTERS_CONFIG_PATH);
    DatasetConfig datasetConfig = getDatasetConfig(datasetConfigpath);
    IcebergCatalogConfig icebergCatalogConfig = getIcebergCatalogConfig(icebergCatalogConfigpath);
    Configuration hadoopConf = gethadoopConf(hadoopConfigpath);
    ConversionSourceProvider conversionSourceProvider =
        getConversionSourceProvider(conversionProviderConfigpath, datasetConfig, hadoopConf);
    List<String> tableFormatList = datasetConfig.getTargetFormats();
    formatConvertor(
        datasetConfig, tableFormatList, icebergCatalogConfig, hadoopConf, conversionSourceProvider);
  }

  static byte[] getCustomConfigurations(String Configpath) throws IOException {
    byte[] customConfig = null;
    if (Configpath != null) {
      customConfig = Files.readAllBytes(Paths.get(Configpath));
    }
    return customConfig;
  }

  @VisibleForTesting
  static Configuration loadHadoopConf(byte[] customConfig) {
    Configuration conf = new Configuration();
    conf.addResource("xtable-hadoop-defaults.xml");
    if (customConfig != null) {
      conf.addResource(new ByteArrayInputStream(customConfig), "customConfigStream");
    }
    return conf;
  }

  /**
   * Loads the conversion configs. The method first loads the default configs and then merges any
   * custom configs provided by the user.
   *
   * @param customConfigs the custom configs provided by the user
   * @return available tableFormatConverters and their configs
   */
  @VisibleForTesting
  static TableFormatConverters loadTableFormatConversionConfigs(byte[] customConfigs)
      throws IOException {
    // get resource stream from default converter config yaml file
    try (InputStream inputStream =
        RunSync.class.getClassLoader().getResourceAsStream("xtable-conversion-defaults.yaml")) {
      TableFormatConverters converters =
          YAML_MAPPER.readValue(inputStream, TableFormatConverters.class);
      if (customConfigs != null) {
        YAML_MAPPER.readerForUpdating(converters).readValue(customConfigs);
      }
      return converters;
    }
  }

  @VisibleForTesting
  static IcebergCatalogConfig loadIcebergCatalogConfig(byte[] customConfigs) throws IOException {
    return customConfigs == null
        ? null
        : YAML_MAPPER.readValue(customConfigs, IcebergCatalogConfig.class);
  }

  @Data
  public static class DatasetConfig {

    /**
     * Table format of the source table. This is a {@link TableFormat} value. Although the format of
     * the source can be auto-detected, it is recommended to specify it explicitly for cases where
     * the directory contains metadata of multiple formats.
     */
    String sourceFormat;

    /** The target formats to sync to. This is a list of {@link TableFormat} values. */
    List<String> targetFormats;

    /** Configuration of the dataset to sync, path, table name, etc. */
    List<Table> datasets;

    @Data
    public static class Table {
      /**
       * The base path of the table to sync. Any authentication configuration needed by HDFS client
       * can be provided using hadoop config file
       */
      String tableBasePath;

      String tableDataPath;

      String tableName;
      String partitionSpec;
      String namespace;
    }
  }

  @Data
  public static class TableFormatConverters {
    /** Map of table format name to the conversion configs. */
    @JsonProperty("tableFormatConverters")
    @JsonMerge
    Map<String, ConversionConfig> tableFormatConverters;

    @Data
    public static class ConversionConfig {
      /**
       * The class name of the {@link ConversionSourceProvider} that will generate the {@link
       * org.apache.xtable.spi.extractor.ConversionSource}.
       */
      String conversionSourceProviderClass;

      /** The class name of the target converter which writes the table metadata. */
      String conversionTargetProviderClass;

      /** the configuration specific to the table format. */
      @JsonMerge Map<String, String> configuration;
    }
  }
}
