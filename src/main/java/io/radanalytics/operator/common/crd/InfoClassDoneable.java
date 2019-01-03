package io.radanalytics.operator.common;

import io.fabric8.kubernetes.api.builder.Function;
import io.fabric8.kubernetes.client.CustomResourceDoneable;


public class InfoClassDoneable<S> extends CustomResourceDoneable<AbstractWatcher.InfoClass<S>> {
    public InfoClassDoneable(AbstractWatcher.InfoClass<S> resource, Function function) {
        super(resource, function);
    }
}
