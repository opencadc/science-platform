package org.opencadc.skaha.utils;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Map;

import static freemarker.template.Configuration.VERSION_2_3_33;

public class FtlConfiguration {
    private static final String templateFolderName = System.getProperty("user.home") + "/config";

    public static Configuration ftlConfiguration(Class<?> className) {
        Configuration configuration = new Configuration(VERSION_2_3_33);
        configuration.setDefaultEncoding("UTF-8");
        configuration.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        configuration.setLogTemplateExceptions(false);
        configuration.setClassForTemplateLoading(className, templateFolderName);
        return configuration;
    }

    public static String template(Class<?> className, String templateName, Map<String, Object> data) throws IOException, TemplateException {
        Template template = ftlConfiguration(className).getTemplate(templateName);
        StringWriter stringWriter = new StringWriter();
        template.process(data, stringWriter);
        return stringWriter.toString();
    }

}
