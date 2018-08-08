package io.radanalytics.operator.common;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.kubernetes.api.model.DoneableConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.Watchable;
import io.radanalytics.operator.Entrypoint;
import io.radanalytics.operator.resource.HasDataHelper;
import io.radanalytics.operator.resource.LabelsHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static io.radanalytics.operator.common.AnsiColors.ANSI_G;
import static io.radanalytics.operator.common.AnsiColors.ANSI_RESET;

/**
 * This abstract class represents the extension point of the abstract-operator library.
 * By extending this class and overriding the methods, you will be able to watch on the
 * configmaps you are interested in and handle the life-cycle of your objects accordingly.
 *
 * Don't forget to add the @Operator annotation of the children classes.
 *
 * @param <T> entity info class that captures the configuration of the objects we are watching
 */
public abstract class AbstractOperator<T extends EntityInfo> {

    protected static final Logger log = LoggerFactory.getLogger(AbstractOperator.class.getName());

    protected KubernetesClient client;
    protected boolean isOpenshift;
    protected String namespace;
    protected final String entityName;
    protected final String prefix;

    private final Map<String, String> selector;
    private final String operatorName;
    private final Class<T> infoClass;

    private volatile Watch configMapWatch;

    public AbstractOperator() {
        this.entityName = getClass().getAnnotation(Operator.class).forKind();
        this.infoClass = (Class<T>) getClass().getAnnotation(Operator.class).infoClass();
        String wannabePrefix = getClass().getAnnotation(Operator.class).prefix();
        this.prefix = wannabePrefix + (!wannabePrefix.endsWith("/") ? "/" : "");
        this.selector = LabelsHelper.forKind(entityName, prefix);
        this.operatorName = "'" + entityName + "' operator";
    }

    /**
     * In this method, the user of the abstract-operator is assumed to handle the creation of
     * a new entity of type T. This method is called when the config map with given type is created.
     * The common use-case would be creating some new resources in the
     * Kubernetes cluster (using @see this.client), like replication controllers with pod specifications
     * and custom images and settings. But one can do arbitrary work here, like calling external APIs, etc.
     *
     * @param entity      entity that represents the config map that has just been created.
     *                    The type of the entity is passed as a type parameter to this class.
     */
    abstract protected void onAdd(T entity);

    /**
     * This method should handle the deletion of the resource that was represented by the config map.
     * The method is called when the corresponding config map is deleted in the Kubernetes cluster.
     * Some suggestion what to do here would be: cleaning the resources, deleting some resources in K8s, etc.
     *
     * @param entity      entity that represents the config map that has just been created.
     *                    The type of the entity is passed as a type parameter to this class.
     */
    abstract protected void onDelete(T entity);

    /**
     * It's called when one modifies the configmap of type 'T' (that passes <code>isSupported</code> check)
     * If this method is not overriden, the implicit behavior is calling <code>onDelete</code> and <code>onAdd</code>.
     *
     * @param entity      entity that represents the config map that has just been created.
     *                    The type of the entity is passed as a type parameter to this class.
     */
    protected void onModify(T entity) {
        onDelete(entity);
        onAdd(entity);
    }

    /**
     * Implicitly only those configmaps with given prefix and kind are being watched, but you can provide additional
     * 'deep' checking in here.
     *
     * @param cm          ConfigMap that is about to be checked
     * @return true if cm is the configmap we are interested in
     */
    protected boolean isSupported(ConfigMap cm) {
        return true;
    }

    /**
     * Converts the configmap representation into T.
     * Normally, you may want to call something like:
     *
     * <code>HasDataHelper.parseCM(FooBar.class, cm);</code> in this method, where FooBar is of type T.
     * This would parse the yaml representation of the configmap's config section and creates an object of type T.
     *
     * @param cm          ConfigMap that is about to be converted to T
     * @return entity of type T
     */
    protected T convert(ConfigMap cm) {
        return HasDataHelper.parseCM(infoClass, cm);
    }

    public String getName() {
        return operatorName;
    }

    public CompletableFuture<Watch> start() {
        log.info("Starting {} for namespace {}", operatorName, namespace);

        CompletableFuture<Watch> future = createConfigMapWatch();
        future.thenApply(res -> {
                this.configMapWatch = res;
                log.info("{} running for namespace {}", operatorName, namespace);
                return res;
        }).exceptionally(e -> {
            log.error("{} startup failed for namespace {}", operatorName, namespace, e.getCause());
            return null;
        });
        return future;
    }

    public void stop() {
        log.info("Stopping {} for namespace {}", operatorName, namespace);
        configMapWatch.close();
        client.close();
    }

    private CompletableFuture<Watch> createConfigMapWatch() {
        CompletableFuture<Watch> cf = CompletableFuture.supplyAsync(() -> {
            MixedOperation<ConfigMap, ConfigMapList, DoneableConfigMap, Resource<ConfigMap, DoneableConfigMap>> aux = client.configMaps();
            Watchable<Watch, Watcher<ConfigMap>> watchable = "*".equals(namespace) ? aux.inAnyNamespace().withLabels(selector) : aux.inNamespace(namespace).withLabels(selector);
            Watch watch = watchable.watch(new Watcher<ConfigMap>() {
                @Override
                public void eventReceived(Action action, ConfigMap cm) {
                    if (isSupported(cm)) {
                        log.info("ConfigMap \n{}\n in namespace {} was {}", cm, namespace, action);
                        T entity = convert(cm);
                        if (entity == null) {
                            log.error("something went wrong, unable to parse {} definition", entityName);
                        }
                        String name = entity.getName();
                        switch (action) {
                            case ADDED:
                                log.info("{}creating{} {}:  \n{}\n", ANSI_G, ANSI_RESET, entityName, name);
                                onAdd(entity);
                                log.info("{} {} has been {}created{}", entityName, name, ANSI_G, ANSI_RESET);
                                break;
                            case DELETED:
                                log.info("{}deleting{} {}:  \n{}\n", ANSI_G, ANSI_RESET, entityName, name);
                                onDelete(entity);
                                log.info("{} {} has been {}deleted{}", entityName, name, ANSI_G, ANSI_RESET);
                                break;
                            case MODIFIED:
                                log.info("{}modifying{} {}:  \n{}\n", ANSI_G, ANSI_RESET, entityName, name);
                                onModify(entity);
                                log.info("{} {} has been {}modified{}", entityName, name, ANSI_G, ANSI_RESET);
                                break;
                            case ERROR:
                                log.error("Failed ConfigMap {} in namespace{} ", cm, namespace);
                                break;
                            default:
                                log.error("Unknown action: {} in namespace {}", action, namespace);
                        }
                    } else {
                        log.error("Unknown CM kind: {}", cm.toString());
                    }
                }

                @Override
                public void onClose(KubernetesClientException e) {
                    if (e != null) {
                        log.error("Watcher closed with exception in namespace {}", namespace, e);
                        recreateConfigMapWatch();
                    } else {
                        log.info("Watcher closed in namespace {}", namespace);
                    }
                }
            });
            return watch;
        }, Entrypoint.EXECUTORS);
        cf.thenApply(w -> {
            log.info("ConfigMap watcher running for labels {}", selector);
            return w;
        }).exceptionally(e -> {
            log.error("ConfigMap watcher failed to start", e.getCause());
            return null;
        });
        return cf;
    }

    private void recreateConfigMapWatch() {
        CompletableFuture<Watch> configMapWatch = createConfigMapWatch();
        configMapWatch.thenApply(res -> {
            log.info("ConfigMap watch recreated in namespace {}", namespace);
            this.configMapWatch = res;
            return res;
        }).exceptionally(e -> {
            log.error("Failed to recreate ConfigMap watch in namespace {}", namespace);
            return null;
        });
    }

    public void setClient(KubernetesClient client) {
        this.client = client;
    }

    public void setOpenshift(boolean openshift) {
        isOpenshift = openshift;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }
}
