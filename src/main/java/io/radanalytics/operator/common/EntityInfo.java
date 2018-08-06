package io.radanalytics.operator.common;

/**
 * Simple interface that captures the information about the object we are interested in in the Kubernetes cluster.
 * Field called 'name' is the only compulsory information and it represents the name of the configmap.
 *
 * By extending this class and adding new fields to it, you can create a rich configuration object. The structure
 * of this object will be expected in the watched config maps and there are also some prepared method for YAML -&gt;
 * 'T extends EntityInfo' conversions prepared in
 * {@link io.radanalytics.operator.resource.HasDataHelper#parseCM(Class, ConfigMap)}.
 */
public interface EntityInfo {
    void setName(String name);
    String getName();
}
