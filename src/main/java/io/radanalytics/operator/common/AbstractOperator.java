package io.radanalytics.operator.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.builder.Function;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinitionBuilder;
import io.fabric8.kubernetes.client.*;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.Watchable;
import io.radanalytics.operator.Entrypoint;
import io.radanalytics.operator.resource.HasDataHelper;
import io.radanalytics.operator.resource.LabelsHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.radanalytics.operator.common.AnsiColors.gr;
import static io.radanalytics.operator.common.AnsiColors.xx;

/**
 * This abstract class represents the extension point of the abstract-operator library.
 * By extending this class and overriding the methods, you will be able to watch on the
 * configmaps or custom resources you are interested in and handle the life-cycle of your
 * objects accordingly.
 *
 * Don't forget to add the @Operator annotation of the children classes.
 *
 * @param <T> entity info class that captures the configuration of the objects we are watching
 */
public abstract class AbstractOperator<T extends EntityInfo> {

    protected static final Logger log = LoggerFactory.getLogger(AbstractOperator.class.getName());

    // client, isOpenshift and namespace are being set in the Entrypoint from the context
    protected KubernetesClient client;
    protected boolean isOpenshift;
    protected String namespace;

    // these fields can be directly set from languages that don't support annotations, like JS
    protected String entityName;
    protected String prefix;
    protected Class<T> infoClass;
    protected boolean isCrd;
    protected boolean enabled = true;
    protected String named;

    private Map<String, String> selector;
    private String operatorName;
    private CustomResourceDefinition crd;

    private volatile Watch watch;

    public AbstractOperator() {
        Operator annotation = getClass().getAnnotation(Operator.class);
        if (annotation != null) {
            this.infoClass = (Class<T>) annotation.forKind();
            this.named = annotation.named();
            this.isCrd = annotation.crd();
            this.enabled = annotation.enabled();
            this.prefix = annotation.prefix();
        } else {
            log.info("Annotation on the operator class not found, falling back to direct field access.");
            log.info("If the initialization fails, it's probably due to the fact that some compulsory fields are missing.");
            log.info("Compulsory fields: infoClass");
        }
    }

    /**
     * In this method, the user of the abstract-operator is assumed to handle the creation of
     * a new entity of type T. This method is called when the config map or custom resource with given
     * type is created.
     * The common use-case would be creating some new resources in the
     * Kubernetes cluster (using @see this.client), like replication controllers with pod specifications
     * and custom images and settings. But one can do arbitrary work here, like calling external APIs, etc.
     *
     * @param entity      entity that represents the config map that has just been created.
     *                    The type of the entity is passed as a type parameter to this class.
     */
    abstract protected void onAdd(T entity);

    /**
     * This method should handle the deletion of the resource that was represented by the config map or custom resource.
     * The method is called when the corresponding config map or custom resource is deleted in the Kubernetes cluster.
     * Some suggestion what to do here would be: cleaning the resources, deleting some resources in K8s, etc.
     *
     * @param entity      entity that represents the config map or custom resource that has just been created.
     *                    The type of the entity is passed as a type parameter to this class.
     */
    abstract protected void onDelete(T entity);

    /**
     * It's called when one modifies the configmap of type 'T' (that passes <code>isSupported</code> check) or custom resource.
     * If this method is not overriden, the implicit behavior is calling <code>onDelete</code> and <code>onAdd</code>.
     *
     * @param entity      entity that represents the config map or custom resource that has just been created.
     *                    The type of the entity is passed as a type parameter to this class.
     */
    protected void onModify(T entity) {
        onDelete(entity);
        onAdd(entity);
    }

    /**
     * Override this method to do arbitrary work before the operator starts listening on configmaps or custom resources.
     */
    protected void onInit() {
        // no-op by default
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
     * If true, start the watcher for this operator. Otherwise it's considered as disabled.
     *
     * @return enabled
     */
    public boolean isEnabled() {
        return this.enabled;
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

    protected T convertCrd(InfoClass info) {
        String name = info.getMetadata().getName();
        ObjectMapper mapper = new ObjectMapper();
        T infoSpec = mapper.convertValue(info.getSpec(), infoClass);
        if (infoSpec == null) { // empty spec
            try {
                infoSpec = infoClass.newInstance();
            } catch (InstantiationException e) {
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }
        if (infoSpec.getName() == null) {
            infoSpec.setName(name);
        }
        return infoSpec;
    }

    public String getName() {
        return operatorName;
    }

    /**
     * Starts the operator and creates the watch
     * @return CompletableFuture
     */
    public CompletableFuture<Watch> start() {
        initInternals();
        this.selector = LabelsHelper.forKind(entityName, prefix);
        boolean ok = checkIntegrity();
        if (!ok) {
            log.warn("Unable to initialize the operator correctly, some compulsory fields are missing.");
            return CompletableFuture.completedFuture(null);
        }

        log.info("Starting {} for namespace {}", operatorName, namespace);

        if (isCrd) {
            this.crd = initCrds();
        }

        // this can be overriden in child operators
        onInit();

        CompletableFuture<Watch> future = isCrd ? createCRDWatch(crd) : createConfigMapWatch();
        future.thenApply(res -> {
                this.watch = res;
                log.info("{} running for namespace {}", operatorName, namespace);
                return res;
        }).exceptionally(e -> {
            log.error("{} startup failed for namespace {}", operatorName, namespace, e.getCause());
            return null;
        });
        return future;
    }

    private boolean checkIntegrity() {
        boolean ok = infoClass != null;
        ok = ok && entityName != null && !entityName.isEmpty();
        ok = ok && prefix != null && !prefix.isEmpty() && prefix.endsWith("/");
        ok = ok && operatorName != null && operatorName.endsWith("operator");
        return ok;
    }

    private void initInternals() {
        entityName = (named != null && !named.isEmpty()) ? named.toLowerCase() : (entityName != null && !entityName.isEmpty()) ? this.entityName.toLowerCase() : (infoClass == null ? "" : infoClass.getSimpleName().toLowerCase());
        isCrd = isCrd || "true".equals(System.getenv("CRD"));
        prefix = prefix == null || prefix.isEmpty() ? getClass().getPackage().getName() : prefix;
        prefix = prefix + (!prefix.endsWith("/") ? "/" : "");
        operatorName = "'" + entityName + "' operator";
    }

    private CustomResourceDefinition initCrds() {
        List<CustomResourceDefinition> crds = client.customResourceDefinitions()
                .list()
                .getItems()
                .stream()
                .filter(p -> this.entityName.equals(p.getSpec().getNames().getKind()))
                .collect(Collectors.toList());
        if (!crds.isEmpty()) {
            return crds.get(0);
        }

        final String newPrefix = prefix.substring(0, prefix.length() - 1);
        final String plural = this.entityName + "s";
        CustomResourceDefinition crd = new CustomResourceDefinitionBuilder()
                .withApiVersion("apiextensions.k8s.io/v1beta1")
                .withNewMetadata().withName(plural + "." + newPrefix)
                .endMetadata()
                .withNewSpec().withNewNames().withKind(this.entityName).withPlural(plural).endNames()
                .withGroup(newPrefix)
                .withVersion("v1")
                .withScope("Namespaced")
                .endSpec()
                .build();
        client.customResourceDefinitions().createOrReplace(crd);

        // register the new crd for json serialization
        io.fabric8.kubernetes.internal.KubernetesDeserializer.registerCustomKind(newPrefix + "/" + crd.getSpec().getVersion() + "#" + this.entityName, InfoClass.class);
        return crd;
    }

    public void stop() {
        log.info("Stopping {} for namespace {}", operatorName, namespace);
        watch.close();
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
                        if (action.equals(Action.ERROR)) {
                            log.error("Failed ConfigMap {} in namespace{} ", cm, namespace);
                        } else {
                            handleAction(action, entity);
                        }
                    } else {
                        log.error("Unknown CM kind: {}", cm.toString());
                    }
                }

                @Override
                public void onClose(KubernetesClientException e) {
                    if (e != null) {
                        log.error("Watcher closed with exception in namespace {}", namespace, e);
                        recreateWatcher();
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

    public static class InfoClass<U> extends CustomResource {
        private U spec;

        public U getSpec() {
            return spec;
        }

        public void setSpec(U spec) {
            this.spec = spec;
        }
    }

    public static class InfoClassDoneable<S> extends CustomResourceDoneable<InfoClass<S>> {
        public InfoClassDoneable(InfoClass<S> resource, Function function) {
            super(resource, function);
        }
    }

    public class InfoList<V> extends CustomResourceList<InfoClass<V>> {
    }

    private CompletableFuture<Watch> createCRDWatch(CustomResourceDefinition crd) {
        CompletableFuture<Watch> cf = CompletableFuture.supplyAsync(() -> {
            MixedOperation<InfoClass, InfoList, InfoClassDoneable, Resource<InfoClass, InfoClassDoneable>> aux =
                    client.customResources(crd, InfoClass.class, InfoList.class, InfoClassDoneable.class);

            Watchable<Watch, Watcher<InfoClass>> watchable = "*".equals(namespace) ? aux.inAnyNamespace() : aux.inNamespace(namespace);
            Watch watch = watchable.watch(new Watcher<InfoClass>() {
                @Override
                public void eventReceived(Action action, InfoClass info) {
                    log.info("Custom resource \n{}\n in namespace {} was {}", info, namespace, action);
                    T entity = convertCrd(info);
                    if (entity == null) {
                        log.error("something went wrong, unable to parse {} definition", entityName);
                    }
                    if (action.equals(Action.ERROR)) {
                        log.error("Failed Custom resource {} in namespace{} ", info, namespace);
                    } else {
                        handleAction(action, entity);
                    }
                }

                @Override
                public void onClose(KubernetesClientException e) {
                    if (e != null) {
                        log.error("Watcher closed with exception in namespace {}", namespace, e);
                        recreateWatcher();
                    } else {
                        log.info("Watcher closed in namespace {}", namespace);
                    }
                }
            });
            return watch;
        }, Entrypoint.EXECUTORS);
        cf.thenApply(w -> {
            log.info("CustomResource watcher running for kinds {}", entityName);
            return w;
        }).exceptionally(e -> {
            log.error("CustomResource watcher failed to start", e.getCause());
            return null;
        });
        return cf;
    }

    private void handleAction(Watcher.Action action, T entity) {
        String name = entity.getName();
        switch (action) {
            case ADDED:
                log.info("{}creating{} {}:  \n{}\n", gr(), xx(), entityName, name);
                onAdd(entity);
                log.info("{} {} has been {}created{}", entityName, name, gr(), xx());
                break;
            case DELETED:
                log.info("{}deleting{} {}:  \n{}\n", gr(), xx(), entityName, name);
                onDelete(entity);
                log.info("{} {} has been {}deleted{}", entityName, name, gr(), xx());
                break;
            case MODIFIED:
                log.info("{}modifying{} {}:  \n{}\n", gr(), xx(), entityName, name);
                onModify(entity);
                log.info("{} {} has been {}modified{}", entityName, name, gr(), xx());
                break;
            default:
                log.error("Unknown action: {} in namespace {}", action, namespace);
        }
    }

    private void recreateWatcher() {
        CompletableFuture<Watch> configMapWatch = isCrd ? createCRDWatch(this.crd): createConfigMapWatch();
        final String crdOrCm = isCrd ? "CustomResource" : "ConfigMap";
        configMapWatch.thenApply(res -> {
            log.info("{} watch recreated in namespace {}", crdOrCm, namespace);
            this.watch = res;
            return res;
        }).exceptionally(e -> {
            log.error("Failed to recreate {} watch in namespace {}", crdOrCm, namespace);
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
