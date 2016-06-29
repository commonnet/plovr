package org.plovr.cli;

import org.plovr.Config;
import org.plovr.ConfigParser;
import org.plovr.TestHandler;
import org.plovr.webdriver.TestRunner;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

public class TestCommand extends AbstractCommandRunner<TestCommandOptions> {

  final private ScheduledExecutorService executorService = Executors.newScheduledThreadPool(8);

  @Override
  TestCommandOptions createOptions() {
    return new TestCommandOptions();
  }

  @Override
  @SuppressWarnings("deprecation")
  int runCommandWithOptions(TestCommandOptions options) throws IOException {
    List<String> arguments = options.getArguments();
    if (arguments.size() < 1) {
      printUsage();
      return 1;
    }

    ClassLoader cl = ClassLoader.getSystemClassLoader();

    URL[] urls = ((URLClassLoader)cl).getURLs();

    for(URL url: urls){
      System.out.println(url.getFile());
    }

    try {
      executorService.submit(new ServeCommandRunner(options)).get();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    int exitCode = 0;

    try {
      List<Future<Boolean>> testResults = new ArrayList<>();
      for (String configFile : arguments) {
        Config config = ConfigParser.parseFile(new File(configFile));

        int timeout = options.getTimeout() * 1000;

        Set<String> relativeTestPaths = TestHandler.getRelativeTestFilePaths(config);
        int offset = 0;
        for (final String relativeTestPath : relativeTestPaths) {
          URL url = new URL(String.format("http://localhost:%d/test/%s/%s",
                  options.getPort(), config.getId(), relativeTestPath));
          final TestRunner testRunner = new TestRunner(url, config.getWebDriverFactories(), timeout);
          testResults.add(executorService.schedule(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
              Thread.yield();
              System.err.printf("START: %s - %s\n", relativeTestPath, Thread.currentThread().toString());
              boolean result = testRunner.run();
              System.err.printf("FINISH: %s - %s\n", relativeTestPath, Thread.currentThread().toString());
              return result;
            }
          }, 1100 * ++offset, TimeUnit.MILLISECONDS));
        }

        for (Future<Boolean> testResult : testResults) {
          boolean result = false;
          try {
            result = testResult.get();
          } catch (Exception e) {
            System.err.printf("Error: %s", e.toString());
            e.printStackTrace();
          }
          if (!result) {
            exitCode = 1;
          }
        }
      }
    } catch (Exception e) {
      System.err.printf("Error: %s", e.toString());
    } finally {
      // TODO: Create a cleaner API to shut down the server.
      executorService.shutdownNow();
    }

    return exitCode;
  }

  @Override
  String getUsageIntro() {
    return "Specify one or more configs whose tests should be run.";
  }

  private static class ServeCommandRunner implements Runnable {

    private final ServeCommandOptions options;

    private ServeCommandRunner(ServeCommandOptions options) {
      this.options = options;
    }

    public void run() {
      ServeCommand command = new ServeCommand();
      try {
        command.runCommandWithOptions(options);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }
}