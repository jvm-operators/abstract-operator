package io.radanalytics.operator.common.crd;

import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinitionBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinitionFluent;
import io.fabric8.kubernetes.api.model.apiextensions.JSONSchemaProps;
import io.fabric8.kubernetes.client.CustomResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.radanalytics.operator.common.EntityInfo;
import io.radanalytics.operator.common.JSONSchemaReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class CrdDeployer {

    protected static final Logger log = LoggerFactory.getLogger(CrdDeployer.class.getName());

    public static CustomResourceDefinition initCrds(KubernetesClient client,
                                                    String prefix,
                                                    String entityName,
                                                    String[] shortNames,
                                                    Class<? extends EntityInfo> infoClass,
                                                    boolean isOpenshift) {
        final String newPrefix = prefix.substring(0, prefix.length() - 1);
        CustomResourceDefinition crdToReturn;

        List<CustomResourceDefinition> crds = client.customResourceDefinitions()
                .list()
                .getItems()
                .stream()
                .filter(p -> entityName.equals(p.getSpec().getNames().getKind()))
                .collect(Collectors.toList());
        if (!crds.isEmpty()) {
            crdToReturn = crds.get(0);
        } else {

            JSONSchemaProps schema = JSONSchemaReader.readSchema(infoClass);
            CustomResourceDefinitionFluent.SpecNested<CustomResourceDefinitionBuilder> builder;

            if (schema != null) {
                builder = getCRDBuilder(newPrefix, entityName, shortNames).withNewValidation()
                        .withNewOpenAPIV3SchemaLike(schema)
                        .endOpenAPIV3Schema()
                        .endValidation();
            } else {
                builder = getCRDBuilder(newPrefix, entityName, shortNames);
            }
            crdToReturn = builder.endSpec().build();
            try {
                client.customResourceDefinitions().createOrReplace(crdToReturn);
            } catch (KubernetesClientException e) {
                // old version of K8s/openshift -> don't use schema validation
                log.warn("Consider upgrading the {}. Your version doesn't support schema validation for custom resources."
                        , isOpenshift ? "OpenShift" : "Kubernetes");
                crdToReturn = getCRDBuilder(newPrefix, entityName, shortNames).endSpec().build();
                client.customResourceDefinitions().createOrReplace(crdToReturn);
            }
        }

        // register the new crd for json serialization
        io.fabric8.kubernetes.internal.KubernetesDeserializer.registerCustomKind(newPrefix + "/" + crdToReturn.getSpec().getVersion() + "#" + entityName, InfoClass.class);
        io.fabric8.kubernetes.internal.KubernetesDeserializer.registerCustomKind(newPrefix + "/" + crdToReturn.getSpec().getVersion() + "#" + entityName + "List", CustomResourceList.class);

        return crdToReturn;
    }

    private static CustomResourceDefinitionFluent.SpecNested<CustomResourceDefinitionBuilder> getCRDBuilder(String prefix, String entityName, String[] shortNames) {
        // plural name must be all lowercase
        final String plural = (entityName + "s").toLowerCase();
        // short names must be all lowercase
        String[] shortNamesLower = Arrays.stream(shortNames)
                                         .map(sn -> sn.toLowerCase())
                                         .toArray(String[]::new);

        return new CustomResourceDefinitionBuilder()
                .withApiVersion("apiextensions.k8s.io/v1beta1")
                .withNewMetadata().withName(plural + "." + prefix)
                .endMetadata()
                .withNewSpec()
                    .withNewNames()
                    .withKind(entityName)
                    .withPlural(plural)
                    .withShortNames(Arrays.asList(shortNamesLower)).endNames()
                .withGroup(prefix)
                .withVersion("v1")
                .withScope("Namespaced");
    }
}
