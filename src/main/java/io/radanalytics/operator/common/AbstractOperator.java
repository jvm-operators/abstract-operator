package io.radanalytics.operator.common;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.kubernetes.api.model.DoneableConfigMap;
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinitionBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinitionFluent;
import io.fabric8.kubernetes.api.model.apiextensions.JSONSchemaProps;
import io.fabric8.kubernetes.client.*;
import io.fabric8.kubernetes.client.dsl.FilterWatchListMultiDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.radanalytics.operator.common.crd.CrdDeployer;
import io.radanalytics.operator.common.crd.InfoClass;
import io.radanalytics.operator.common.crd.InfoClassDoneable;
import io.radanalytics.operator.common.crd.InfoList;
import io.radanalytics.operator.resource.LabelsHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This abstract class represents the extension point of the abstract-operator library.
 * By extending this class and overriding the methods, you will be able to watch on the
 * config maps or custom resources you are interested in and handle the life-cycle of your
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

    protected volatile boolean fullReconciliationRun = false;

    private Map<String, String> selector;
    private String operatorName;
    private CustomResourceDefinition crd;

    private volatile AbstractWatcher watch;

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
     * @param entity      entity that represents the config map (or CR) that has just been created.
     *                    The type of the entity is passed as a type parameter to this class.
     */
    abstract protected void onAdd(T entity);

    /**
     * Override this method if you want to manually handle the case when it watches for the events in the all
     * namespaces (<code>WATCH_NAMESPACES="*"</code>).
     *
     *
     * @param entity     entity that represents the config map (or CR) that has just been created.
     *      *            The type of the entity is passed as a type parameter to this class.
     * @param namespace  namespace in which the resources should be created.
     */
    protected void onAdd(T entity, String namespace) {
        onAction(entity, namespace, this::onAdd);
    }

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
     * Override this method if you want to manually handle the case when it watches for the events in the all
     * namespaces (<code>WATCH_NAMESPACES="*"</code>).
     *
     *
     * @param entity     entity that represents the config map (or CR) that has just been created.
     *      *            The type of the entity is passed as a type parameter to this class.
     * @param namespace  namespace in which the resources should be created.
     */
    protected void onDelete(T entity, String namespace) {
        onAction(entity, namespace, this::onDelete);
    }

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
     * Override this method if you want to manually handle the case when it watches for the events in the all
     * namespaces (<code>WATCH_NAMESPACES="*"</code>).
     *
     *
     * @param entity     entity that represents the config map (or CR) that has just been created.
     *      *            The type of the entity is passed as a type parameter to this class.
     * @param namespace  namespace in which the resources should be created.
     */
    protected void onModify(T entity, String namespace) {
        onAction(entity, namespace, this::onModify);
    }

    private void onAction(T entity, String namespace, Consumer<T> handler) {
        if ("*".equals(this.namespace)) {
            //synchronized (this.watch) { // events from the watch should be serialized (1 thread)
            try {
                this.namespace = namespace;
                handler.accept(entity);
            } finally {
                this.namespace = "*";
            }
            //}
        } else {
            handler.accept(entity);
        }
    }

    /**
     * Override this method to do arbitrary work before the operator starts listening on configmaps or custom resources.
     */
    protected void onInit() {
        // no-op by default
    }

    /**
     * Override this method to do a full reconciliation.
     */
    public void fullReconciliation() {
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
        return ConfigMapWatcher.defaultConvert(infoClass, cm);
    }

    protected T convertCr(InfoClass info) {
        return CustomResourceWatcher.defaultConvert(infoClass, info);
    }

    public String getName() {
        return operatorName;
    }

    /**
     * Starts the operator and creates the watch
     * @return CompletableFuture
     */
    public CompletableFuture<? extends AbstractWatcher> start() {
        initInternals();
        this.selector = LabelsHelper.forKind(entityName, prefix);
        boolean ok = checkIntegrity();
        if (!ok) {
            log.warn("Unable to initialize the operator correctly, some compulsory fields are missing.");
            return CompletableFuture.completedFuture(null);
        }

        log.info("Starting {} for namespace {}", operatorName, namespace);

        if (isCrd) {
            this.crd = CrdDeployer.initCrds(client, prefix, entityName, infoClass, isOpenshift);
        }

        // onInit() can be overriden in child operators
        onInit();

        CompletableFuture<? extends AbstractWatcher<T>> future = initializeWatcher();
        future.thenApply(res -> {
                this.watch = res;
                log.info("{}{} running{} for namespace {}", AnsiColors.gr(), operatorName, AnsiColors.xx(), namespace);
                return res;
        }).exceptionally(e -> {
            log.error("{} startup failed for namespace {}", operatorName, namespace, e.getCause());
            return null;
        });
        return future;
    }

    private CompletableFuture<? extends AbstractWatcher<T>> initializeWatcher() {
        CompletableFuture<? extends AbstractWatcher<T>> future;
        if (isCrd) {
            CustomResourceWatcher.Builder<T> crBuilder = new CustomResourceWatcher.Builder<>();
            CustomResourceWatcher crWatcher = crBuilder.withClient(client)
                    .withCrd(crd)
                    .withEntityName(entityName)
                    .withNamespace(namespace)
                    .withConvert(this::convertCr)
                    .withOnAdd(this::onAdd)
                    .withOnDelete(this::onDelete)
                    .withOnModify(this::onModify)
                    .build();
            future = crWatcher.watch();
        } else {
            ConfigMapWatcher.Builder<T> cmBuilder = new ConfigMapWatcher.Builder<>();
            ConfigMapWatcher cmWatcher = cmBuilder.withClient(client)
                    .withSelector(selector)
                    .withEntityName(entityName)
                    .withNamespace(namespace)
                    .withConvert(this::convert)
                    .withOnAdd(this::onAdd)
                    .withOnDelete(this::onDelete)
                    .withOnModify(this::onModify)
                    .withPredicate(this::isSupported)
                    .build();
            future = cmWatcher.watch();
        }
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
         // prefer "named" for the entity name, otherwise "entityName" and finally the converted class name.
        if (named != null && !named.isEmpty()) {
            entityName = named;
        } else if (entityName != null && !entityName.isEmpty()) {
            entityName = this.entityName;
        } else if (infoClass != null) {
            entityName = infoClass.getSimpleName();
        } else {
            entityName = "";
        }
        isCrd = isCrd || "true".equals(System.getenv("CRD"));
        prefix = prefix == null || prefix.isEmpty() ? getClass().getPackage().getName() : prefix;
        prefix = prefix + (!prefix.endsWith("/") ? "/" : "");
        operatorName = "'" + entityName + "' operator";
    }

    public void stop() {
        log.info("Stopping {} for namespace {}", operatorName, namespace);
        watch.close();
        client.close();
    }

    /**
     * Call this method in the concrete operator to obtain the desired state of the system. This can be especially handy
     * during the fullReconciliation. Rule of thumb is that if you are overriding <code>fullReconciliation</code>, you
     * should also override this method and call it from <code>fullReconciliation()</code> to ensure that the real state
     * is the same as the desired state.
     *
     * @return returns the set of 'T's that correspond to the CMs or CRs that have been created in the K8s
     */
    protected Set<T> getDesiredSet() {
        Set<T> desiredSet;
        if (isCrd) {
            MixedOperation<InfoClass, InfoList, InfoClassDoneable, Resource<InfoClass, InfoClassDoneable>> aux1 =
                    client.customResources(crd, InfoClass.class, InfoList.class, InfoClassDoneable.class);
            FilterWatchListMultiDeletable<InfoClass, InfoList, Boolean, Watch, Watcher<InfoClass>> aux2 =
                    "*".equals(namespace) ? aux1.inAnyNamespace() : aux1.inNamespace(namespace);
            CustomResourceList<InfoClass> listAux = aux2.list();
            List<InfoClass> items = listAux.getItems();
            desiredSet = items.stream().flatMap(item -> {
                try {
                    return Stream.of(convertCr(item));
                } catch (Exception e) {
                    // ignore this CR
                    return Stream.empty();
                }
            }).collect(Collectors.toSet());
        } else {
            MixedOperation<ConfigMap, ConfigMapList, DoneableConfigMap, Resource<ConfigMap, DoneableConfigMap>> aux1 =
                    client.configMaps();
            FilterWatchListMultiDeletable<ConfigMap, ConfigMapList, Boolean, Watch, Watcher<ConfigMap>> aux2 =
                    "*".equals(namespace) ? aux1.inAnyNamespace() : aux1.inNamespace(namespace);
            desiredSet = aux2.withLabels(selector)
                    .list()
                    .getItems()
                    .stream()
                    .flatMap(item -> {
                        try {
                            return Stream.of(convert(item));
                        } catch (Exception e) {
                            // ignore this CM
                            return Stream.empty();
                        }
                    }).collect(Collectors.toSet());
        }
        return desiredSet;
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

    public void setEntityName(String entityName) {
        this.entityName = entityName;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public void setInfoClass(Class<T> infoClass) {
        this.infoClass = infoClass;
    }

    public void setCrd(boolean crd) {
        isCrd = crd;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setNamed(String named) {
        this.named = named;
    }

    public void setFullReconciliationRun(boolean fullReconciliationRun) {
        this.fullReconciliationRun = fullReconciliationRun;
        this.watch.setFullReconciliationRun(true);
    }
}
