package io.radanalytics.operator.common;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.apiextensions.JSONSchemaProps;

import java.io.IOException;
import java.net.URL;

public class JSONSchemaReader {

    public static JSONSchemaProps readSchema(Class infoClass) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        char[] chars = infoClass.getSimpleName().toCharArray();
        chars[0] = Character.toLowerCase(chars[0]);
        String url = "/schema/" + new String(chars) + ".json";
        URL in = infoClass.getResource(url);
        try {
            return mapper.readValue(in, JSONSchemaProps.class);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
}
