package io.radanalytics.operator;

import com.jcabi.manifests.Manifests;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.radanalytics.operator.common.AbstractOperator;
import io.radanalytics.operator.common.Operator;
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

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.radanalytics.operator.common.AnsiColors.*;

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
//        KubernetesClient client = new DefaultKubernetesClient(getUnsafeOkHttpClient(), new ConfigBuilder().build());
        KubernetesClient client = new DefaultKubernetesClient();
        boolean isOpenshift = isOnOpenShift(client);
        run(client, isOpenshift, config).exceptionally(ex -> {
            log.error("Unable to start operator for 1 or more namespace", ex);
            System.exit(1);
            return null;
        });
    }

    private static CompletableFuture<Void> run(KubernetesClient client, boolean isOpenShift, OperatorConfig config) {
        printInfo();

        if (isOpenShift) {
            log.info("OpenShift environment detected.");
        } else {
            log.info("Kubernetes environment detected.");
        }

        List<CompletableFuture> futures = new ArrayList<>();
        if (null == config.getNamespaces()) { // get the current namespace
            String namespace = client.getNamespace();
            CompletableFuture future = runForNamespace(client, isOpenShift, namespace);
            futures.add(future);
        } else {
            for (String namespace : config.getNamespaces()) {
                CompletableFuture future = runForNamespace(client, isOpenShift, namespace);
                futures.add(future);
            }
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[]{}));
    }

    private static CompletableFuture<Void> runForNamespace(KubernetesClient client, boolean isOpenShift, String namespace) {
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
                futures.add(future);
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
        urlBuilder.addPathSegment("/oapi");

        OkHttpClient httpClient = getOkHttpClient();
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
            log.debug("{} returned {}. We are on OpenShift.", url, response.code());
        } else {
            log.debug("{} returned {}. We are not on OpenShift. Assuming, we are on Kubernetes.", url, response.code());
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