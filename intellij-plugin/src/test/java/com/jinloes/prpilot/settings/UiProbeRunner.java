package com.jinloes.prpilot.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs headless Swing UI probes inside one reusable child JVM per {@code ide.ui.scale} so {@code
 * JBUI} sees a genuine startup scale without cold-booting the IntelliJ classpath for every test.
 */
final class UiProbeRunner {

    private static final String DONE_PREFIX = "__UI_PROBE_DONE__\t";
    private static final ConcurrentHashMap<String, Worker> WORKERS = new ConcurrentHashMap<>();
    private static final ExecutorService IO_POOL =
            Executors.newCachedThreadPool(
                    runnable -> {
                        Thread thread = new Thread(runnable, "ui-probe-io");
                        thread.setDaemon(true);
                        return thread;
                    });

    static {
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> WORKERS.values().forEach(Worker::destroy),
                                "ui-probe-cleanup"));
    }

    private UiProbeRunner() {}

    static void runAtDefaultScales(Class<?> probeHost, String methodName) throws Exception {
        for (String uiScale : List.of("1.0", "2.0")) {
            run(probeHost, methodName, uiScale);
        }
    }

    static void run(Class<?> probeHost, String methodName, String uiScale) throws Exception {
        Worker worker = WORKERS.computeIfAbsent(uiScale, Worker::start);
        ProbeOutcome outcome = worker.execute(probeHost.getName(), methodName);
        if (!outcome.completed()) {
            WORKERS.remove(uiScale, worker);
            worker.destroy();
        }
        assertThat(outcome.completed())
                .withFailMessage("UI probe timed out at %s scale:%n%s", uiScale, outcome.output())
                .isTrue();
        assertThat(outcome.succeeded())
                .withFailMessage("UI probe failed at %s scale:%n%s", uiScale, outcome.output())
                .isTrue();
    }

    private record ProbeOutcome(boolean completed, boolean succeeded, String output) {}

    private static final class Worker {
        private final Process process;
        private final BufferedWriter stdin;
        private final BufferedReader stdout;
        private final Path argFile;

        private Worker(Process process, BufferedWriter stdin, BufferedReader stdout, Path argFile) {
            this.process = process;
            this.stdin = stdin;
            this.stdout = stdout;
            this.argFile = argFile;
        }

        static Worker start(String uiScale) {
            try {
                Path argFile = Files.createTempFile("pr-pilot-ui-probe-", ".args");
                String escapedCp =
                        System.getProperty("java.class.path")
                                .replace("\\", "\\\\")
                                .replace("\"", "\\\"");
                Files.writeString(argFile, "-cp\n\"" + escapedCp + "\"\n", StandardCharsets.UTF_8);
                String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
                Process process =
                        new ProcessBuilder(
                                        java,
                                        "@" + argFile.toAbsolutePath(),
                                        "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
                                        "--add-opens=java.desktop/javax.swing=ALL-UNNAMED",
                                        "-Djava.awt.headless=true",
                                        "-Dide.ui.scale=" + uiScale,
                                        "-Dos.name=Linux",
                                        WorkerMain.class.getName())
                                .redirectErrorStream(true)
                                .start();
                return new Worker(
                        process,
                        new BufferedWriter(
                                new OutputStreamWriter(
                                        process.getOutputStream(), StandardCharsets.UTF_8)),
                        new BufferedReader(
                                new InputStreamReader(
                                        process.getInputStream(), StandardCharsets.UTF_8)),
                        argFile);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to start UI probe worker", e);
            }
        }

        synchronized ProbeOutcome execute(String className, String methodName) {
            CompletableFuture<ProbeOutcome> call =
                    CompletableFuture.supplyAsync(
                            () -> sendAndRead(className, methodName), IO_POOL);
            try {
                return call.get(30, TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                call.cancel(true);
                return new ProbeOutcome(false, false, className + "#" + methodName);
            } catch (Exception e) {
                return new ProbeOutcome(true, false, stackTrace(e));
            }
        }

        private ProbeOutcome sendAndRead(String className, String methodName) {
            StringBuilder captured = new StringBuilder();
            try {
                stdin.write(className + "\t" + methodName + "\n");
                stdin.flush();
                String line;
                while ((line = stdout.readLine()) != null) {
                    if (line.startsWith(DONE_PREFIX)) {
                        String[] parts = line.substring(DONE_PREFIX.length()).split("\t", 2);
                        boolean ok = "OK".equals(parts[0]);
                        int extraLines = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
                        for (int i = 0; i < extraLines; i++) {
                            String detail = stdout.readLine();
                            if (detail != null) {
                                captured.append(detail).append('\n');
                            }
                        }
                        return new ProbeOutcome(true, ok, captured.toString());
                    }
                    captured.append(line).append('\n');
                }
                return new ProbeOutcome(false, false, captured.toString());
            } catch (IOException e) {
                captured.append(stackTrace(e));
                return new ProbeOutcome(false, false, captured.toString());
            }
        }

        void destroy() {
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                Files.deleteIfExists(argFile);
            } catch (IOException ignored) {
                // Best-effort temp cleanup at test shutdown.
            }
        }
    }

    public static final class WorkerMain {
        public static void main(String[] args) throws Exception {
            PrintStream protocolOut = System.out;
            BufferedReader in =
                    new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String request;
            while ((request = in.readLine()) != null) {
                String[] parts = request.split("\t", 2);
                try {
                    Method method = Class.forName(parts[0]).getDeclaredMethod(parts[1]);
                    method.setAccessible(true);
                    method.invoke(null);
                    protocolOut.print(DONE_PREFIX + "OK\t0\n");
                    protocolOut.flush();
                } catch (Throwable failure) {
                    Throwable cause =
                            failure instanceof InvocationTargetException ite
                                            && ite.getCause() != null
                                    ? ite.getCause()
                                    : failure;
                    String[] lines = stackTrace(cause).split("\r?\n", -1);
                    protocolOut.print(DONE_PREFIX + "FAIL\t" + lines.length + "\n");
                    for (String line : lines) {
                        protocolOut.print(line + "\n");
                    }
                    protocolOut.flush();
                }
            }
        }
    }

    private static String stackTrace(Throwable throwable) {
        StringWriter buffer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(buffer));
        return buffer.toString();
    }
}
