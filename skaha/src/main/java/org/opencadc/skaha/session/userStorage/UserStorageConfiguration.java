package org.opencadc.skaha.session.userStorage;

import java.nio.file.Path;
import java.util.Collection;
import org.apache.commons.configuration2.CombinedConfiguration;
import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.EnvironmentConfiguration;
import org.apache.commons.configuration2.SystemConfiguration;
import org.apache.commons.configuration2.convert.DefaultConversionHandler;
import org.apache.commons.configuration2.ex.ConversionException;
import org.apache.commons.configuration2.interpol.ConfigurationInterpolator;
import org.apache.commons.configuration2.tree.MergeCombiner;

public class UserStorageConfiguration {
    private static final String SKAHA_USER_STORAGE_TOP_LEVEL_DIRECTORY = "SKAHA_USER_STORAGE_TOP_LEVEL_DIRECTORY";
    private static final String SKAHA_USER_STORAGE_HOME_BASE_DIRECTORY = "SKAHA_USER_STORAGE_HOME_BASE_DIRECTORY";
    private static final String SKAHA_USER_STORAGE_PROJECTS_BASE_DIRECTORY =
            "SKAHA_USER_STORAGE_PROJECTS_BASE_DIRECTORY";

    public final Path topLevelDirectory;
    public final Path homeBaseDirectory;
    public final Path projectsBaseDirectory;

    public static UserStorageConfiguration fromEnv() {
        final CombinedConfiguration configuration = new CombinedConfiguration(new MergeCombiner());
        configuration.setConversionHandler(new PathConversionHandler());

        configuration.addConfiguration(new EnvironmentConfiguration());

        // Allow for system properties to override environment variables.  Useful for testing.
        configuration.addConfiguration(new SystemConfiguration());
        return new UserStorageConfiguration(configuration);
    }

    private UserStorageConfiguration(final Configuration configuration) {
        this.homeBaseDirectory =
                configuration.get(Path.class, UserStorageConfiguration.SKAHA_USER_STORAGE_HOME_BASE_DIRECTORY);
        this.projectsBaseDirectory =
                configuration.get(Path.class, UserStorageConfiguration.SKAHA_USER_STORAGE_PROJECTS_BASE_DIRECTORY);
        this.topLevelDirectory =
                configuration.get(Path.class, UserStorageConfiguration.SKAHA_USER_STORAGE_TOP_LEVEL_DIRECTORY);
    }

    private static class PathConversionHandler extends DefaultConversionHandler {
        @Override
        public <T> T to(Object src, Class<T> targetCls, ConfigurationInterpolator ci) {
            if (Path.class.isAssignableFrom(targetCls)) {
                if (src instanceof String) {
                    return targetCls.cast(Path.of((String) src));
                } else if (src instanceof Path) {
                    return targetCls.cast(src);
                } else {
                    throw new ConversionException(
                            "Cannot convert " + src.getClass().getName() + " to Path");
                }
            } else {
                return super.to(src, targetCls, ci);
            }
        }

        @Override
        public Object toArray(Object src, Class<?> elemClass, ConfigurationInterpolator ci) {
            return null;
        }

        @Override
        public <T> void toCollection(
                Object src, Class<T> elemClass, ConfigurationInterpolator ci, Collection<T> dest) {}
    }
}
