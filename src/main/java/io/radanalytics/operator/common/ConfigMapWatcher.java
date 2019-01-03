package io.radanalytics.operator.common;

import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;

public class CustomResourceWatcher<T extends EntityInfo> extends AbstractWatcher<T> {

    // use via builder
    private CustomResourceWatcher(String namespace,
                                  String entityName,
                                  KubernetesClient client,
                                  CustomResourceDefinition crd,
                                  BiConsumer<T, String> onAdd,
                                  BiConsumer<T, String> onDelete,
                                  BiConsumer<T, String> onModify,
                                  Function<InfoClass, T> convert) {
        super(true, namespace, entityName, client, crd, null, onAdd, onDelete, onModify, null, null, convert);
    }

    public static class Builder<T> {
        private String namespace = "*";
        private String entityName;
        private KubernetesClient client;
        private CustomResourceDefinition crd;

        private BiConsumer<T, String> onAdd;
        private BiConsumer<T, String> onDelete;
        private BiConsumer<T, String> onModify;
        private Function<InfoClass, T> convert;

        public void withNamespace(String namespace) {
            this.namespace = namespace;
        }

        public void withEntityName(String entityName) {
            this.entityName = entityName;
        }

        public void withClient(KubernetesClient client) {
            this.client = client;
        }

        public void withCrd(CustomResourceDefinition crd) {
            this.crd = crd;
        }

        public void withOnAdd(BiConsumer<T, String> onAdd) {
            this.onAdd = onAdd;
        }

        public void withOnDelete(BiConsumer<T, String> onDelete) {
            this.onDelete = onDelete;
        }

        public void withOnModify(BiConsumer<T, String> onModify) {
            this.onModify = onModify;
        }

        public void withConvert(Function<InfoClass, T> convert) {
            this.convert = convert;
        }

        public CustomResourceWatcher build() {
            return new CustomResourceWatcher(namespace, entityName, client, crd, onAdd, onDelete, onModify, convert);
        }
    }

    @Override
    public CompletableFuture<CustomResourceWatcher<T>> watch() {
        return createCRDWatch().thenApply(watch -> this);
    }
}


