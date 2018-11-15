package io.radanalytics.operator;

import com.jcabi.manifests.Manifests;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.utils.HttpClientUtils;
import io.prometheus.client.exporter.HTTPServer;
import io.prometheus.client.hotspot.DefaultExports;
import io.radanalytics.operator.common.AbstractOperator;
import io.radanalytics.operator.common.AnsiColors;
import io.radanalytics.operator.common.OperatorConfig;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.reflections.Reflections;
import org.reflections.ReflectionsException;
import org.reflections.scanners.ResourcesScanner;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.scanners.TypeAnnotationsScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

import static io.radanalytics.operator.common.AnsiColors.*;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Entry point class that contains the main method and should bootstrap all the registered operators
 * that are present on the class path. It scans the class path for those classes that have the @Operator
 * annotations on them. Also the package name must start with io.
 */
public class Entrypoint {

    private static final Logger log = LoggerFactory.getLogger(Entrypoint.class.getName());

    public static ExecutorService EXECUTORS = Executors.newFixedThreadPool(10);

    public static void main(String[] args) {
        log.info("Starting..");
        OperatorConfig config = OperatorConfig.fromMap(System.getenv());
        KubernetesClient client = new DefaultKubernetesClient();
        boolean isOpenshift = isOnOpenShift(client);
        CompletableFuture<Void> future = run(client, isOpenshift, config).exceptionally(ex -> {
            log.error("Unable to start operator for one or more namespaces", ex);
            System.exit(1);
            return null;
        });
        if (config.isMetrics()) {
            CompletableFuture<Optional<HTTPServer>> maybeMetricServer = future.thenCompose(s -> runMetrics(isOpenshift, config));
            // todo: shutdown hook and top it if necessary
        }
    }

    private static CompletableFuture<Void> run(KubernetesClient client, boolean isOpenShift, OperatorConfig config) {
        printInfo();

        if (isOpenShift) {
            log.info("{}OpenShift{} environment detected.", AnsiColors.ye(), AnsiColors.xx());
        } else {
            log.info("{}Kubernetes{} environment detected.", AnsiColors.ye(), AnsiColors.xx());
        }

        List<CompletableFuture> futures = new ArrayList<>();
        if (null == config.getNamespaces()) { // get the current namespace
            String namespace = client.getNamespace();
            CompletableFuture future = runForNamespace(client, isOpenShift, namespace, config.getReconciliationIntervalS());
            futures.add(future);
        } else {
            for (String namespace : config.getNamespaces()) {
                CompletableFuture future = runForNamespace(client, isOpenShift, namespace, config.getReconciliationIntervalS());
                futures.add(future);
            }
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[]{}));
    }

    private static CompletableFuture<Optional<HTTPServer>> runMetrics(boolean isOpenShift, OperatorConfig config) {
        HTTPServer httpServer = null;
        try {
            log.info("Starting a simple HTTP server for exposing internal metrics..");
            httpServer = new HTTPServer(config.getMetricsPort());
            log.info("{}metrics server{} listens on port {}", AnsiColors.ye(), AnsiColors.xx(), config.getMetricsPort());
            // todo: create also the service and for openshift also expose the service (?)
        } catch (IOException e) {
            log.error("Can't start metrics server because of: {} ", e.getMessage());
            e.printStackTrace();
        }
        if (config.isMetricsJvm()) {
            DefaultExports.initialize();
        }
        final Optional<HTTPServer> maybeServer = Optional.of(httpServer);
        return CompletableFuture.supplyAsync(() -> maybeServer);
    }

    private static CompletableFuture<Void> runForNamespace(KubernetesClient client, boolean isOpenShift, String namespace, long reconInterval) {
        List<ClassLoader> classLoadersList = new LinkedList<>();
        classLoadersList.add(ClasspathHelper.contextClassLoader());
        classLoadersList.add(ClasspathHelper.staticClassLoader());

        Set<Class<? extends AbstractOperator>> operatorClasses = null;
        try {
            Reflections reflections = new Reflections(new ConfigurationBuilder()
                    .setScanners(new TypeAnnotationsScanner(), new SubTypesScanner(false), new ResourcesScanner())
                    .setUrls(ClasspathHelper.forClassLoader(classLoadersList.toArray(new ClassLoader[0]))));
            operatorClasses = reflections.getSubTypesOf(AbstractOperator.class);
        } catch (ReflectionsException re) {
            log.debug(re.getMessage());
            // np, swallow
        }

        List<Future> futures = new ArrayList<>();
        operatorClasses.stream().forEach(operatorClass -> {
            try {
                if (!AbstractOperator.class.isAssignableFrom(operatorClass)) {
                    log.error("Class {} annotated with @Operator doesn't extend the AbstractOperator", operatorClass);
                    System.exit(1);
                }

                final AbstractOperator operator = ((Class<? extends AbstractOperator>)operatorClass).newInstance();
                if (!operator.isEnabled()) {
                    log.info("Skipping initialization of {} operator", operatorClass);
                    return;
                }
                operator.setClient(client);
                operator.setNamespace(namespace);
                operator.setOpenshift(isOpenShift);

                CompletableFuture<Watch> future = operator.start().thenApply(res -> {
                    log.info("{} started in namespace {}", operator.getName(), namespace);
                    return res;
                }).exceptionally(ex -> {
                    log.error("{} in namespace {} failed to start", operator.getName(), namespace, ((Throwable)ex).getCause());
                    System.exit(1);
                    return null;
                });

                ScheduledExecutorService s = Executors.newScheduledThreadPool(1);
                int delay = new Random().nextInt(15);
                ScheduledFuture<?> scheduledFuture =
                        s.scheduleAtFixedRate(() -> operator.fullReconciliation(), delay, reconInterval, SECONDS);
                log.info("full reconciliation scheduled (periodically each {} seconds)", reconInterval);
                log.info("the first full reconciliation is happening in {} seconds", delay);

                futures.add(future);
//                futures.add(scheduledFuture);
            } catch (InstantiationException | IllegalAccessException e) {
                e.printStackTrace();
            }
        });
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[]{}));
    }

    private static boolean isOnOpenShift(KubernetesClient client) {
        URL kubernetesApi = client.getMasterUrl();

        HttpUrl.Builder urlBuilder = new HttpUrl.Builder();
        urlBuilder.host(kubernetesApi.getHost());

        if (kubernetesApi.getPort() == -1) {
            urlBuilder.port(kubernetesApi.getDefaultPort());
        } else {
            urlBuilder.port(kubernetesApi.getPort());
        }
        if (kubernetesApi.getProtocol().equals("https")) {
            urlBuilder.scheme("https");
        }
        urlBuilder.addPathSegment("oapi");

        OkHttpClient httpClient = HttpClientUtils.createHttpClient(new ConfigBuilder().build());
        HttpUrl url = urlBuilder.build();
        Response response;
        try {
            response = httpClient.newCall(new Request.Builder().url(url).build()).execute();
        } catch (IOException e) {
            e.printStackTrace();
            log.error("Failed to distinguish between Kubernetes and OpenShift");
            log.warn("Let's assume we are on K8s");
            return false;
        }
        boolean success = response.isSuccessful();
        if (success) {
            log.info("{} returned {}. We are on OpenShift.", url, response.code());
        } else {
            log.info("{} returned {}. We are not on OpenShift. Assuming, we are on Kubernetes.", url, response.code());
        }
        return success;
    }

    private static void printInfo() {
        String gitSha = "unknown";
        String version = "unknown";
        try {
            gitSha = Manifests.read("Implementation-Build");
            version = Entrypoint.class.getPackage().getImplementationVersion();
        } catch (Exception e) {
            // ignore, not critical
        }
        log.info("\n{}Operator{} has started in version {}{}{}. {}\n", re(), xx(), gr(),
                version, xx(), FOO);
        if (!gitSha.isEmpty()) {
            log.info("Git sha: {}{}{}", ye(), gitSha, xx());
        }
        log.info("==================\n");
    }
}
