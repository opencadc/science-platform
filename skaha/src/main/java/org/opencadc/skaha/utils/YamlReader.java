package org.opencadc.skaha.utils;

import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Map;

public class YamlReader {
    public static Map<String, Object> read(String filePath) throws FileNotFoundException {
        InputStream inputStream = new FileInputStream(filePath);
        Yaml yaml = new Yaml();
        return yaml.load(inputStream);
    }
}
