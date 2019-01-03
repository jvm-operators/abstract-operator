package io.radanalytics.operator.common.crd;

import io.fabric8.kubernetes.client.CustomResource;

public class InfoClass<U> extends CustomResource {
    private U spec;

    public U getSpec() {
        return spec;
    }

    public void setSpec(U spec) {
        this.spec = spec;
    }
}
