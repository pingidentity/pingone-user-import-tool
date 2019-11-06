package com.pingidentity.userimport;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.util.CommandLineTool;
import com.unboundid.util.FixedRateBarrier;
import com.unboundid.util.ObjectPair;
import com.unboundid.util.args.ArgumentException;
import com.unboundid.util.args.ArgumentParser;
import com.unboundid.util.args.BooleanArgument;
import com.unboundid.util.args.FileArgument;
import com.unboundid.util.args.IntegerArgument;
import com.unboundid.util.args.StringArgument;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.oauth2.client.OAuth2RestTemplate;
import org.springframework.security.oauth2.client.token.grant.client.ClientCredentialsResourceDetails;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

public class UserImportTool extends CommandLineTool {

  private static final Logger log = LoggerFactory.getLogger(UserImportTool.class);

  private FileArgument csvFileArgument;
  private FileArgument rejectsFileArgument;
  private StringArgument environmentIdArgument;
  private StringArgument populationIdArgument;
  private StringArgument clientIdArgument;
  private StringArgument clientSecretArgument;
  private StringArgument authUriArgument;
  private StringArgument platformUriArgument;
  private BooleanArgument forcePasswordChangeArgument;
  private IntegerArgument numThreadsArgument;
  private IntegerArgument ratePerSecond;

  private Set<String> headerNames = new HashSet<>();

  private static final int DEFAULT_NUM_THREADS = 20;

  public static void main(String[] args) {
    UserImportTool tool = new UserImportTool();
    ResultCode resultCode = tool.runTool(args);
    if (resultCode != ResultCode.SUCCESS) {
      System.exit(resultCode.intValue());
    }
  }

  private UserImportTool() {
    super(System.out, System.err);
  }

  public String getToolName() {
    return "user-import-tool";
  }

  public String getToolDescription() {
    return "Imports users into PingOne from a CSV file. Before using the tool, you'll need to create a Worker "
        + "application that has the Identity Data Admin role for the environment. When you execute the tool, you'll "
        + "need to provide the CSV file, environment ID, population ID, and client credentials, at a minimum. Any "
        + "users that are rejected will be written to a rejects CSV file that can be manually adjusted and reprocessed "
        + "by the tool. The following headers are supported in the CSV file. At a minimum, the 'username' header must "
        + "be included. Additionally, 'enabled' will default to 'true', if omitted.\n"
        + "name.honorificPrefix,name.given,name.middle,name.family,name.honorificSuffix,name.formatted,primaryPhone,mobilePhone,email,username,password,enabled";
  }

  @Override
  public LinkedHashMap<String[], String> getExampleUsages() {
    LinkedHashMap<String[], String> exampleUsages = new LinkedHashMap<>();
    exampleUsages.put(
        new String[]{
            "--csvFile", "users.csv",
            "--environmentId", UUID.randomUUID().toString(),
            "--populationId", UUID.randomUUID().toString(),
            "--clientId", UUID.randomUUID().toString(),
            "--clientSecret", "********"
        },
        "Imports users from a users.csv file into PingOne"
    );
    return exampleUsages;
  }

  public void addToolArguments(ArgumentParser parser) throws ArgumentException {
    csvFileArgument = new FileArgument(
        'f',
        "csvFile",
        true,
        1,
        "{csvFile}",
        "The path to the CSV file that includes user data",
        true,
        true,
        true,
        false
    );
    parser.addArgument(csvFileArgument);

    rejectsFileArgument = new FileArgument(
        'r',
        "rejectsFile",
        false,
        1,
        "{rejectsFile}",
        "The path to the CSV file where rejects will be written. This file can be updated and reprocessed to fix bad "
            + "data. If not provided, it will default to 'rejects.csv'.",
        false,
        true,
        true,
        false,
        Collections.singletonList(new File("rejects.csv"))
    );
    parser.addArgument(rejectsFileArgument);

    environmentIdArgument = new StringArgument(
        'e',
        "environmentId",
        true,
        1,
        "{environmentId}",
        "The ID of the environment to import users into"
    );
    parser.addArgument(environmentIdArgument);

    populationIdArgument = new StringArgument(
        'p',
        "populationId",
        true,
        1,
        "{populationId}",
        "The ID of the population to import users into"
    );
    parser.addArgument(populationIdArgument);

    clientIdArgument = new StringArgument(
        'c',
        "clientId",
        true,
        1,
        "{clientId}",
        "The ID of PingOne Worker application"
    );
    parser.addArgument(clientIdArgument);

    clientSecretArgument = new StringArgument(
        's',
        "clientSecret",
        true,
        1,
        "{clientSecret}",
        "The secret of PingOne Worker application"
    );
    parser.addArgument(clientSecretArgument);

    authUriArgument = new StringArgument(
        'a',
        "authUri",
        false,
        1,
        "{authUri}",
        "Auth Host base URI. e.g. auth.pingone.com",
        "auth.pingone.com"
    );
    parser.addArgument(authUriArgument);

    platformUriArgument = new StringArgument(
        'b',
        "platformUri",
        false,
        1,
        "{platformUri}",
        "Platform Base URI. e.g. api.pingone.com",
        "api.pingone.com"
    );
    parser.addArgument(platformUriArgument);

    forcePasswordChangeArgument = new BooleanArgument(
        null,
        "forcePasswordChange",
        "If this argument is present, all imported users must change their password on the next login."
    );
    parser.addArgument(forcePasswordChangeArgument);

    numThreadsArgument = new IntegerArgument(
        'n',
        "numThreads",
        false,
        1,
        "{numThreads}",
        "The number of threads to use to concurrently import users. This defaults to " + DEFAULT_NUM_THREADS
            + " if not provided, and the maximum rate will be capped at 100 per second regardless of the number of "
            + "threads that are used.",
        1,
        2000,
        DEFAULT_NUM_THREADS
    );
    parser.addArgument(numThreadsArgument);

    ratePerSecond = new IntegerArgument(
        'i',
        "ratePerSecond",
        false,
        1,
        "{ratePerSecond}",
        "Max rate per second. Default is 100 request/second",
        100
    );
    ratePerSecond.setHidden(true);
    parser.addArgument(ratePerSecond);

  }

  public ResultCode doToolProcessing() {
    // create an OAuth resource to get an access token
    String environmentId = environmentIdArgument.getValue();
    String authUri = authUriArgument.getValue();
    String platformUri = platformUriArgument.getValue();
    ClientCredentialsResourceDetails resource = new ClientCredentialsResourceDetails();
    resource.setAccessTokenUri("https://" + authUri + "/" + environmentId + "/as/token");
    resource.setClientId(clientIdArgument.getValue());
    resource.setClientSecret(clientSecretArgument.getValue());

    // construct some stuff needed by the RestTemplate
    List<HttpMessageConverter<?>> messageConverters = new ArrayList<>();
    MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
    converter.setSupportedMediaTypes(Collections.singletonList(MediaType.ALL));
    messageConverters.add(converter);

    // create a thread pool that limits to 100 per second to stay under the rate limit
    int numThreads = numThreadsArgument.getValue();
    ExecutorService executorService = Executors.newFixedThreadPool(numThreads);
    int rateSecond = ratePerSecond.getValue();
    FixedRateBarrier barrier = new FixedRateBarrier(1000, rateSecond);

    File csvFile = csvFileArgument.getValue();
    URI uri = UriComponentsBuilder.fromUriString("https://" + platformUri + "/v1/environments/{envId}/users")
        .buildAndExpand(environmentId)
        .toUri();
    AtomicInteger total = new AtomicInteger(0);
    AtomicInteger success = new AtomicInteger(0);
    AtomicInteger error = new AtomicInteger(0);
    Set<Long> errorLineNumbers = Collections.synchronizedSet(new HashSet<>());
    try (CSVParser parser = CSVParser.parse(csvFile, Charset.defaultCharset(), CSVFormat.DEFAULT.withHeader())) {
      headerNames.addAll(parser.getHeaderNames());
      log.info("Importing users from CSV file: {}", csvFile.getAbsolutePath());

      // create threads to import users
      long startTime = System.currentTimeMillis();
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < numThreads; i++) {
        futures.add(executorService.submit(() -> {
          RestTemplate client = new OAuth2RestTemplate(resource);
          client.setMessageConverters(messageConverters);

          ObjectPair<CSVRecord, Long> csvRecord;
          while ((csvRecord = getCSVRecord(parser)) != null) {
            barrier.await();
            int currentTotal = total.incrementAndGet();
            ObjectNode user = userFromCSVRecord(csvRecord.getFirst());
            RequestEntity request = RequestEntity.method(HttpMethod.POST, uri)
                .contentType(MediaType.parseMediaType("application/vnd.pingidentity.user.import+json"))
                .body(user);
            try {
              client.exchange(request, ObjectNode.class);
              success.incrementAndGet();
            } catch (Exception e) {
              error.incrementAndGet();
              Long lineNumber = csvRecord.getSecond();
              errorLineNumbers.add(lineNumber);
              log.error("Encountered error importing user on line {}: {}", lineNumber, user, e);
              if (e instanceof RestClientResponseException) {
                log.error("Error response:\n{}", ((RestClientResponseException) e).getResponseBodyAsString());
              }
            }
            if (currentTotal % 10 == 0) {
              long duration = System.currentTimeMillis() - startTime;
              double rate = currentTotal / (duration / 1000.0);
              log.info("Processed {} users at an average of {} per second",
                  currentTotal, String.format("%.1f", rate));
            }
          }
        }));
      }

      // wait for the threads and shut down the pool
      for (Future<?> future : futures) {
        try {
          future.get();
        } catch (Exception e) {
          log.error("Encountered error waiting for thread termination", e);
        }
      }
      executorService.shutdown();
    } catch (IOException e) {
      log.error("Could not read CSV file", e);
      e.printStackTrace();
    }

    log.info("Successfully imported {} users with {} errors.", success.get(), error.get());
    writeRejectsFile(errorLineNumbers);
    return ResultCode.SUCCESS;
  }

  private synchronized ObjectPair<CSVRecord, Long> getCSVRecord(CSVParser csvParser) {
    if (csvParser.iterator().hasNext()) {
      return new ObjectPair<>(csvParser.iterator().next(), csvParser.getCurrentLineNumber());
    }
    return null;
  }

  private ObjectNode userFromCSVRecord(CSVRecord csvRecord) {
    ObjectNode user = JsonNodeFactory.instance.objectNode();
    addUserAttribute(csvRecord, user, "username", "username");
    addUserAttribute(csvRecord, user, "email", "email");
    addUserAttribute(csvRecord, user, "primaryPhone", "primaryPhone");
    addUserAttribute(csvRecord, user, "mobilePhone", "mobilePhone");

    user.with("population").put("id", populationIdArgument.getValue());

    if (headerNames.contains("password")) {
      String password = csvRecord.get("password");
      if (password != null && !password.trim().isEmpty()) {
        ObjectNode passwordNode = user.with("password");
        passwordNode.put("value", password.trim());
        if (forcePasswordChangeArgument.isPresent()) {
          passwordNode.put("forceChange", true);
        }
      }
    }

    if (headerNames.contains("enabled")) {
      String enabled = csvRecord.get("enabled");
      user.put("enabled", enabled == null || enabled.trim().isEmpty() ? true : Boolean.valueOf(enabled));
    }

    ObjectNode name = user.with("name");
    addUserAttribute(csvRecord, name, "name.honorificPrefix", "honorificPrefix");
    addUserAttribute(csvRecord, name, "name.given", "given");
    addUserAttribute(csvRecord, name, "name.middle", "middle");
    addUserAttribute(csvRecord, name, "name.family", "family");
    addUserAttribute(csvRecord, name, "name.honorificSuffix", "honorificSuffix");
    addUserAttribute(csvRecord, name, "name.formatted", "formatted");
    return user;
  }

  private void addUserAttribute(CSVRecord csvRecord, ObjectNode objectNode, String csvHeader, String attribute) {
    if (!headerNames.contains(csvHeader)) {
      return;
    }

    String value = csvRecord.get(csvHeader);
    if (value != null && !value.trim().isEmpty()) {
      objectNode.put(attribute, value.trim());
    }
  }

  private void writeRejectsFile(Set<Long> errorLineNumbers) {
    if (errorLineNumbers.isEmpty()) {
      return;
    }

    File rejectsFile = rejectsFileArgument.getValue();
    if (rejectsFile.exists()) {
      log.info("Deleting existing rejects file: {}", rejectsFile.getAbsolutePath());
      if (!rejectsFile.delete()) {
        log.warn("Could not delete rejects file");
      }
    }

    try (BufferedReader reader = new BufferedReader(new FileReader(csvFileArgument.getValue()));
        FileWriter writer = new FileWriter(rejectsFile)) {
      // include the first line for the header
      errorLineNumbers.add(1L);
      long lineNumber = 0;
      String line;
      while ((line = reader.readLine()) != null) {
        lineNumber++;
        if (errorLineNumbers.contains(lineNumber)) {
          writer.write(line);
          writer.write('\n');
        }
      }
    } catch (Exception e) {
      log.error("Encountered error creating rejects file", e);
    }

    log.info("Wrote rejects to file: {}", rejectsFile.getAbsolutePath());
  }
}
