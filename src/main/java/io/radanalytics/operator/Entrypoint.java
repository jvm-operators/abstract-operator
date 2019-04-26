package io.radanalytics.operator;

import com.jcabi.manifests.Manifests;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.prometheus.client.Gauge;
import io.prometheus.client.log4j.InstrumentedAppender;
import io.radanalytics.operator.common.AbstractOperator;
import io.radanalytics.operator.common.AnsiColors;
import io.radanalytics.operator.common.OperatorConfig;
import okhttp3.OkHttpClient;
import org.reflections.Reflections;
import org.reflections.ReflectionsException;
import org.reflections.scanners.ResourcesScanner;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.scanners.TypeAnnotationsScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static io.radanalytics.operator.common.AnsiColors.*;
import static io.radanalytics.operator.common.OperatorConfig.ALL_NAMESPACES;
import static io.radanalytics.operator.common.OperatorConfig.SAME_NAMESPACE;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Entry point class that contains the main method and should bootstrap all the registered operators
 * that are present on the class path. It scans the class path for those classes that have the
 * {@link io.radanalytics.operator.common.Operator} annotations on them or extends the {@link AbstractOperator}.
 */
public class Entrypoint {

    private static final Logger log = LoggerFactory.getLogger(Entrypoint.class.getName());

    private static OperatorConfig config;
    private static KubernetesClient client;
    private static ExecutorService executors;

    public static void main(String[] args) {
        log.info("Starting..");
        config = OperatorConfig.fromMap(System.getenv());
//        Config c = new Config(String, String, String, boolean, boolean, String, String, String, String, String,
// String, String, String , String , String , String , int , int , int , int , long , long , int , int , String ,
// String , String[] , Map<Integer, String> , String , TlsVersion[] , long , long , String , String , String ,
// String , String , String , String , String[] , Map<String, List<String>> );
//        Config c = new Config(null, null, null, false, false, null, null, null, null, null, null, null, null, null, null);
//        new ConfigBuilder
//        client = new DefaultKubernetesClient(getOkHttpClient(),);
        client = new DefaultKubernetesClient();
        boolean isOpenshift = isOnOpenShift();
        log.error("cajk0");
        CompletableFuture<Void> future = run(isOpenshift).exceptionally(ex -> {
            log.error("Unable to start operator for one or more namespaces", ex);
            System.exit(1);
            return null;
        });
        if (config.isMetrics()) {
//            CompletableFuture<Optional<HTTPServer>> maybeMetricServer = future.thenCompose(s -> runMetrics(isOpenshift));
        }
        log.error("cajk1");
        future.join();
        log.error("cajk2");
    }

    private static CompletableFuture<Void> run(boolean isOpenShift) {
        printInfo();
        log.error("222cajk1");
        if (isOpenShift) {
            log.info("{}OpenShift{} environment detected.", AnsiColors.ye(), AnsiColors.xx());
        } else {
            log.info("{}Kubernetes{} environment detected.", AnsiColors.ye(), AnsiColors.xx());
        }
        log.error("cajkaa");

        List<CompletableFuture> futures = new ArrayList<>();
        if (SAME_NAMESPACE.equals(config.getNamespaces().iterator().next())) { // current namespace
            log.error("cajk3");
            String namespace = client.getNamespace();
            CompletableFuture future = runForNamespace(isOpenShift, namespace, config.getReconciliationIntervalS(), 0);
            log.error("cajk4");
            futures.add(future);
        } else {
            if (ALL_NAMESPACES.equals(config.getNamespaces().iterator().next())) {
                log.error("cajk5");
                CompletableFuture future = runForNamespace(isOpenShift, ALL_NAMESPACES, config.getReconciliationIntervalS(), 0);
                log.error("cajk6");
                futures.add(future);
            } else {
                log.error("cajk7");
                Iterator<String> ns;
                int i;
                for (ns = config.getNamespaces().iterator(), i = 0; i < config.getNamespaces().size(); i++) {
                    CompletableFuture future = runForNamespace(isOpenShift, ns.next(), config.getReconciliationIntervalS(), i);
                    futures.add(future);
                }
            }
        }
        log.error("cajk8");
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[]{}));
    }

//    private static CompletableFuture<Optional<HTTPServer>> runMetrics(boolean isOpenShift) {
//        HTTPServer httpServer = null;
//        try {
//            log.info("Starting a simple HTTP server for exposing internal metrics..");
//            httpServer = new HTTPServer(config.getMetricsPort());
//            log.info("{}metrics server{} listens on port {}", AnsiColors.ye(), AnsiColors.xx(), config.getMetricsPort());
//            // todo: create also the service and for openshift also expose the service (?)
//        } catch (IOException e) {
//            log.error("Can't start metrics server because of: {} ", e.getMessage());
//            e.printStackTrace();
//        }
//        if (config.isMetricsJvm()) {
//            DefaultExports.initialize();
//        }
//        final Optional<HTTPServer> maybeServer = Optional.of(httpServer);
//        return CompletableFuture.supplyAsync(() -> maybeServer);
//    }

    private static CompletableFuture<Void> runForNamespace(boolean isOpenShift, String namespace, long reconInterval, int delay) {
        log.error("cajk9");
        List<ClassLoader> classLoadersList = new LinkedList<>();
        classLoadersList.add(ClasspathHelper.contextClassLoader());
        classLoadersList.add(ClasspathHelper.staticClassLoader());

        final List<Class<? extends AbstractOperator>> operatorClasses = new ArrayList<>();
        try {
            operatorClasses.add((Class<? extends AbstractOperator>) Class.forName("io.radanalytics.operator.cluster.SparkClusterOperator"));
            log.error("cajk9.3");
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            log.error("cajk9.4");
        }
        try {
            log.error("cajk9.5");
            Reflections reflections = new Reflections(new ConfigurationBuilder()
                    .setScanners(new TypeAnnotationsScanner(), new SubTypesScanner(false), new ResourcesScanner())
                    .setUrls(ClasspathHelper.forClassLoader(classLoadersList.toArray(new ClassLoader[0]))));
            log.error("cajk9.8");
            operatorClasses.addAll(reflections.getSubTypesOf(AbstractOperator.class));
            log.error("cajk9.9");
        } catch (ReflectionsException re) {
            log.debug(re.getMessage());
            log.error("cajk10");
            // np, swallow
        }

        log.error("cajk11");

        List<Future> futures = new ArrayList<>();
        final int operatorNumber = operatorClasses.size();
        log.error("cajk12 operatorNumber: " + operatorNumber + "  classes: " + operatorClasses);
        IntStream.range(0, operatorNumber).forEach(operatorIndex -> {
            log.error("cajk13");
            final Class<? extends AbstractOperator> operatorClass = operatorClasses.get(operatorIndex);
            try {
                if (!AbstractOperator.class.isAssignableFrom(operatorClass)) {
                    log.error("Class {} annotated with @Operator doesn't extend the AbstractOperator", operatorClass);
                    System.exit(1);
                }

                final AbstractOperator operator = operatorClass.newInstance();  
                if (!operator.isEnabled()) {
                    log.info("Skipping initialization of {} operator", operatorClass);
                    return;
                }

                log.error("cajk14");
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
                int realDelay = (delay * operatorNumber) + operatorIndex + 2;
                ScheduledFuture<?> scheduledFuture =
                        s.scheduleAtFixedRate(() -> {
                            try {
                                operator.fullReconciliation();
                                operator.setFullReconciliationRun(true);
                            } catch (Throwable t) {
                                log.warn("error during full reconciliation: {}", t.getMessage());
                                t.printStackTrace();
                            }
                        }, realDelay, reconInterval, SECONDS);
                log.info("full reconciliation for {} scheduled (periodically each {} seconds)", operator.getName(), reconInterval);
                log.info("the first full reconciliation for {} is happening in {} seconds", operator.getName(), realDelay);

                futures.add(future);
//                futures.add(scheduledFuture);
            } catch (InstantiationException | IllegalAccessException e) {
                log.error("cajk16: " + e);
                e.printStackTrace();
            }
        });
        log.error("cajk15");
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[]{}));
    }

    private static boolean isOnOpenShift() {
//        URL kubernetesApi = client.getMasterUrl();
//
//        HttpUrl.Builder urlBuilder = new HttpUrl.Builder();
//        urlBuilder.host(kubernetesApi.getHost());
//
//        if (kubernetesApi.getPort() == -1) {
//            urlBuilder.port(kubernetesApi.getDefaultPort());
//        } else {
//            urlBuilder.port(kubernetesApi.getPort());
//        }
//        if (kubernetesApi.getProtocol().equals("https")) {
//            urlBuilder.scheme("https");
//        }
//        urlBuilder.addPathSegment("apis/route.openshift.io/v1");
//
//        OkHttpClient httpClient = HttpClientUtils.createHttpClient(new ConfigBuilder().build());
//        HttpUrl url = urlBuilder.build();
//        Response response;
//        try {
//            response = httpClient.newCall(new Request.Builder().url(url).build()).execute();
//        } catch (IOException e) {
//            e.printStackTrace();
//            log.error("Failed to distinguish between Kubernetes and OpenShift");
//            log.warn("Let's assume we are on K8s");
//            return false;
//        }
//        boolean success = response.isSuccessful();
//        if (success) {
//            log.info("{} returned {}. We are on OpenShift.", url, response.code());
//        } else {
//            log.info("{} returned {}. We are not on OpenShift. Assuming, we are on Kubernetes.", url, response.code());
//        }
//        return success;
        return true;
    }

    private static void printInfo() {
        String gitSha = "unknown";
        String version = "unknown";
        try {
            version = Entrypoint.class.getPackage().getImplementationVersion();
            gitSha = Manifests.read("Implementation-Build");
        } catch (Exception e) {
            // ignore, not critical
        }

        if(config.isMetrics()) {
            registerMetrics(gitSha, version);
        }

        log.info("\n{}Operator{} has started in version {}{}{}.\n", re(), xx(), gr(),
                version, xx());
        if (!gitSha.isEmpty()) {
            log.info("Git sha: {}{}{}", ye(), gitSha, xx());
        }
        log.info("==================\n");
    }

    private static void registerMetrics(String gitSha, String version) {
        List<String> labels = new ArrayList<>();
        List<String> values = new ArrayList<>();

        labels.addAll(Arrays.asList("gitSha", "version",
                "CRD",
                "COLORS",
                OperatorConfig.WATCH_NAMESPACE,
                OperatorConfig.METRICS,
                OperatorConfig.METRICS_JVM,
                OperatorConfig.METRICS_PORT,
                OperatorConfig.FULL_RECONCILIATION_INTERVAL_S,
                OperatorConfig.OPERATOR_OPERATION_TIMEOUT_MS
        ));
        values.addAll(Arrays.asList(gitSha, version,
                Optional.ofNullable(System.getenv().get("CRD")).orElse("true"),
                Optional.ofNullable(System.getenv().get("COLORS")).orElse("true"),
                SAME_NAMESPACE.equals(config.getNamespaces().iterator().next()) ? client.getNamespace() : config.getNamespaces().toString(),
                String.valueOf(config.isMetrics()),
                String.valueOf(config.isMetricsJvm()),
                String.valueOf(config.getMetricsPort()),
                String.valueOf(config.getReconciliationIntervalS()),
                String.valueOf(config.getOperationTimeoutMs())
        ));

        Gauge.build()
                .name("operator_info")
                .help("Basic information about the abstract operator library.")
                .labelNames(labels.toArray(new String[]{}))
                .register()
                .labels(values.toArray(new String[]{}))
                .set(1);

        // add log appender for metrics
        final org.apache.log4j.Logger rootLogger = org.apache.log4j.Logger.getRootLogger();
        InstrumentedAppender metricsLogAppender = new InstrumentedAppender();
        metricsLogAppender.setName("metrics");
        rootLogger.addAppender(metricsLogAppender);
    }

    public static ExecutorService getExecutors() {
        if (null == executors) {
            executors = Executors.newFixedThreadPool(10);
        }
        return executors;
    }

    private static OkHttpClient getOkHttpClient() {
        try {
            // Create a trust manager that does not validate certificate chains
            final X509TrustManager trustAllCerts = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {}
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {}
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[]{};
                }
            };
            final SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, new X509TrustManager[]{trustAllCerts}, new SecureRandom());
            final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
            OkHttpClient.Builder builder = new OkHttpClient.Builder();
            builder.sslSocketFactory(sslSocketFactory, trustAllCerts);
            builder.hostnameVerifier((hostname, session) -> true);
            OkHttpClient okHttpClient = builder.build();
            return okHttpClient;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
