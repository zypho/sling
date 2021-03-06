/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.caconfig.resource.impl.def;

import static org.apache.sling.caconfig.resource.impl.def.ConfigurationResourceNameConstants.PROPERTY_CONFIG_COLLECTION_INHERIT;
import static org.apache.sling.caconfig.resource.impl.def.ConfigurationResourceNameConstants.PROPERTY_CONFIG_PROPERTY_INHERIT;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.apache.commons.collections.iterators.ArrayIterator;
import org.apache.commons.collections.iterators.IteratorChain;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.apache.sling.caconfig.resource.impl.ContextPathStrategyMultiplexer;
import org.apache.sling.caconfig.resource.impl.util.PathEliminateDuplicatesIterator;
import org.apache.sling.caconfig.resource.impl.util.PathParentExpandIterator;
import org.apache.sling.caconfig.resource.spi.CollectionInheritanceDecider;
import org.apache.sling.caconfig.resource.spi.ConfigurationResourceResolvingStrategy;
import org.apache.sling.caconfig.resource.spi.ContextResource;
import org.apache.sling.caconfig.resource.spi.InheritanceDecision;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.FieldOption;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(service=ConfigurationResourceResolvingStrategy.class)
@Designate(ocd=DefaultConfigurationResourceResolvingStrategy.Config.class)
public class DefaultConfigurationResourceResolvingStrategy implements ConfigurationResourceResolvingStrategy {

    @ObjectClassDefinition(name="Apache Sling Context-Aware Default Configuration Resource Resolving Strategy",
                           description="Standardized access to configurations in the resource tree.")
    public static @interface Config {

        @AttributeDefinition(name="Enabled",
                description = "Enable this configuration resourcer resolving strategy.")
        boolean enabled() default true;

        @AttributeDefinition(name="Configurations path",
                             description = "Paths where the configurations are stored in.")
        String configPath() default "/conf";

        @AttributeDefinition(name="Fallback paths",
                description = "Global fallback configurations, ordered from most specific (checked first) to least specific.")
        String[] fallbackPaths() default {"/conf/global", "/apps/conf", "/libs/conf"};

        @AttributeDefinition(name="Config collection inheritance property names",
                description = "Additional property names to " + PROPERTY_CONFIG_COLLECTION_INHERIT + " to handle configuration inheritance. The names are used in the order defined, "
                            + "always starting with " + PROPERTY_CONFIG_COLLECTION_INHERIT + ". Once a property with a value is found, that value is used and the following property names are skipped.")
        String[] configCollectionInheritancePropertyNames();

        @AttributeDefinition(name="Config property inheritance property names",
                description = "Additional property names to " + PROPERTY_CONFIG_PROPERTY_INHERIT + " to handle property inheritance. The names are used in the order defined, "
                            + "always starting with " + PROPERTY_CONFIG_PROPERTY_INHERIT + ". Once a property with a value is found, that value is used and the following property names are skipped.")
        String[] configPropertyInheritancePropertyNames();
    }

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private volatile Config config;

    @Reference
    private ContextPathStrategyMultiplexer contextPathStrategy;

    @Reference(cardinality=ReferenceCardinality.MULTIPLE,
            policy=ReferencePolicy.DYNAMIC,
            fieldOption=FieldOption.REPLACE)
    private volatile List<CollectionInheritanceDecider> collectionInheritanceDeciders;

    Config getConfiguration() {
        return this.config;
    }

    @Activate
    private void activate(final Config config) {
        this.config = config;
    }

    @Deactivate
    private void deactivate() {
        this.config = null;
    }

    @SuppressWarnings("unchecked")
    Iterator<String> getResolvePaths(final Resource contentResource, final String bucketName) {
        return new IteratorChain(
            // add all config references found in resource hierarchy
            findConfigRefs(contentResource, bucketName),
            // finally add the global fallbacks
            new ArrayIterator(this.config.fallbackPaths())
        );
    }

    /**
     * Check the name.
     * A name must not be null and relative.
     * @param name The name
     * @return {@code true} if it is valid
     */
    private boolean checkName(final String name) {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.contains("../") ) {
            return false;
        }
        return true;
    }

    /**
     * Searches the resource hierarchy upwards for all config references and returns them.
     * @param refs List to add found resources to
     * @param startResource Resource to start searching
     */
    @SuppressWarnings("unchecked")
    private Iterator<String> findConfigRefs(final Resource startResource, final String bucketName) {
        final String notAllowedPostfix = "/" + bucketName;

        // collect all context path resources
        final Iterator<ContextResource> contextResources = contextPathStrategy.findContextResources(startResource);

        // get config resource path for each context resource, filter out items where not reference could be resolved
        final Iterator<String> configPaths = new Iterator<String>() {

            private final List<ContextResource> relativePaths = new ArrayList<>();

            private String next = seek();

            private String useFromRelativePathsWith;

            private String seek() {
                String val = null;
                while ( val == null && (useFromRelativePathsWith != null || contextResources.hasNext()) ) {
                    if ( useFromRelativePathsWith != null ) {
                        final ContextResource contextResource = relativePaths.remove(relativePaths.size() - 1);
                        val = checkPath(contextResource, useFromRelativePathsWith + "/" + contextResource.getConfigRef(), notAllowedPostfix);

                        if ( relativePaths.isEmpty() ) {
                            useFromRelativePathsWith = null;
                        }
                    } else {
                        final ContextResource contextResource = contextResources.next();
                        val = contextResource.getConfigRef();

                        // if absolute path found we are (probably) done
                        if (val != null && val.startsWith("/")) {
                            val = checkPath(contextResource, val, notAllowedPostfix);
                        }

                        if (val != null) {
                            logger.trace("Reference '{}' found at {}",
                                    contextResource.getConfigRef(), contextResource.getResource().getPath());
                            final boolean isAbsolute = val.startsWith("/");
                            if ( isAbsolute && !relativePaths.isEmpty() ) {
                                useFromRelativePathsWith = val;
                                val = null;
                            } else if ( !isAbsolute ) {
                                relativePaths.add(0, contextResource);
                                val = null;
                            }
                        }
                    }
                }
                if ( val == null && !relativePaths.isEmpty() ) {
                    logger.error("Relative references not used as no absolute reference was found: {}", relativePaths);
                }
                return val;
            }

            @Override
            public boolean hasNext() {
                return next != null;
            }

            @Override
            public String next() {
                if ( next == null ) {
                    throw new NoSuchElementException();
                }
                final String result = next;
                next = seek();
                return result;
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };

        // expand paths and eliminate duplicates
        return new PathEliminateDuplicatesIterator(new PathParentExpandIterator(config.configPath(), configPaths));
    }

    private String checkPath(final ContextResource contextResource, String ref, final String notAllowedPostfix) {
        // combine full path if relativeRef is present
        ref = ResourceUtil.normalize(ref);

        if (ref != null && ref.endsWith(notAllowedPostfix) ) {
            logger.warn("Ignoring reference to {} from {} - Probably misconfigured as it ends with '{}'",
                    contextResource.getConfigRef(), contextResource.getResource().getPath(), notAllowedPostfix);
            ref = null;
        }
        if (ref != null && !isAllowedConfigPath(ref)) {
            logger.warn("Ignoring reference to {} from {} - not in allowed paths.",
                    contextResource.getConfigRef(), contextResource.getResource().getPath());
            ref = null;
        }

        if (ref != null && isFallbackConfigPath(ref)) {
            logger.warn("Ignoring reference to {} from {} - already a fallback path.",
                    contextResource.getConfigRef(), contextResource.getResource().getPath());
            ref = null;
        }

        return ref;
    }

    private boolean isAllowedConfigPath(String path) {
        if (logger.isTraceEnabled()) {
            logger.trace("- checking if '{}' starts with {}", path, config.configPath());
        }
        return path.startsWith(config.configPath() + "/");
    }

    private boolean isFallbackConfigPath(final String ref) {
        for(final String path : this.config.fallbackPaths()) {
            if (StringUtils.equals(ref, path) || StringUtils.startsWith(ref, path + "/")) {
                return true;
            }
        }
        return false;
    }

    private boolean isEnabledAndParamsValid(final Resource contentResource, final String bucketName, final String configName) {
        return config.enabled() && contentResource != null && checkName(bucketName) && checkName(configName);
    }

    private String buildResourcePath(String path, String name) {
        return ResourceUtil.normalize(path + "/" + name);
    }

    @Override
    public Resource getResource(final Resource contentResource, final String bucketName, final String configName) {
        if (!isEnabledAndParamsValid(contentResource, bucketName, configName)) {
            return null;
        }
        String name = bucketName + "/" + configName;
        logger.debug("Searching {} for resource {}", name, contentResource.getPath());

        // strategy: find first item among all configured paths
        int idx = 1;
        Iterator<String> paths = getResolvePaths(contentResource, bucketName);
        Resource configResource = null;
        Map<String,Object> configValueMap = null;
        boolean propertyInheritance = false;
        while (paths.hasNext()) {
            final String path = paths.next();
            final Resource item = contentResource.getResourceResolver().getResource(buildResourcePath(path, name));
            if (item != null) {
                logger.debug("Resolved config item at [{}]: {}", idx, item.getPath());

                if (propertyInheritance) {
                    // merge property map with values from inheritance parent
                    Map<String,Object> mergedValueMap = new HashMap<>(item.getValueMap());
                    mergedValueMap.putAll(configValueMap);
                    configValueMap = mergedValueMap;
                }
                else {
                    configResource = item;
                    configValueMap = item.getValueMap();
                }

                // check property inheritance mode on current level - should we check on next-highest level as well?
                propertyInheritance = getBooleanValue(item.getValueMap(), PROPERTY_CONFIG_PROPERTY_INHERIT, config.configPropertyInheritancePropertyNames());
                if (!propertyInheritance) {
                    break;
                }
            }
            idx++;
        }

        if (configResource == null) {
            logger.debug("Could not resolve any config item for '{}' (or no permissions to read it)", name);
            return null;
        }

        if (configValueMap instanceof ValueMap) {
            // valuemap was not merged - return original resource with original valuemap
            return configResource;
        }
        else {
            return new ConfigurationResourceWrapper(configResource, new ValueMapDecorator(configValueMap));
        }
    }

    private boolean include(final List<CollectionInheritanceDecider> deciders,
            final String bucketName,
            final Resource resource,
            final Set<String> blockedItems) {
        boolean result = !blockedItems.contains(resource.getName());
        if ( result && deciders != null && !deciders.isEmpty() ) {
            for(int i=deciders.size()-1;i>=0;i--) {
                final InheritanceDecision decision = deciders.get(i).decide(resource, bucketName);
                if ( decision == InheritanceDecision.EXCLUDE ) {
                    result = false;
                    break;
                } else if ( decision == InheritanceDecision.BLOCK ) {
                    result = false;
                    blockedItems.add(resource.getName());
                    break;
                }
            }
        }
        return result;
    }

    @Override
    public Collection<Resource> getResourceCollection(final Resource contentResource, final String bucketName, final String configName) {
        if (!isEnabledAndParamsValid(contentResource, bucketName, configName)) {
            return Collections.emptyList();
        }
        String name = bucketName + "/" + configName;
        if (logger.isTraceEnabled()) {
            logger.trace("- searching for list '{}'", name);
        }

        final Map<String,Resource> result = new LinkedHashMap<>();
        final Map<String,Map<String,Object>> configValueMaps = new HashMap<>();

        final List<CollectionInheritanceDecider> deciders = this.collectionInheritanceDeciders;
        final Set<String> blockedItems = new HashSet<>();

        int idx = 1;
        Iterator<String> paths = getResolvePaths(contentResource, bucketName);
        boolean inheritCollection = false;
        boolean inheritProperties = false;
        boolean inherit = false;
        boolean propertyInheritanceApplied = false;
        while (paths.hasNext()) {
            final String path = paths.next();
            Resource item = contentResource.getResourceResolver().getResource(buildResourcePath(path, name));
            if (item != null) {

                if (logger.isTraceEnabled()) {
                    logger.trace("+ resolved config item at [{}]: {}", idx, item.getPath());
                }

                for (Resource child : item.getChildren()) {
                    if (isValidResourceCollectionItem(child) && include(deciders, bucketName, child, blockedItems)) {
                        if ((!inherit || inheritCollection) && !result.containsKey(child.getName())) {
                            result.put(child.getName(), child);
                            configValueMaps.put(child.getName(), child.getValueMap());
                        }
                        else if (inheritProperties && configValueMaps.containsKey(child.getName())) {
                            // merge property map with values from inheritance parent
                            Map<String,Object> mergedValueMap = new HashMap<>(child.getValueMap());
                            mergedValueMap.putAll(configValueMaps.get(child.getName()));
                            configValueMaps.put(child.getName(), mergedValueMap);
                            propertyInheritanceApplied = true;
                        }
                    }
                }

                // check collection and property inheritance mode on current level - should we check on next-highest level as well?
                final ValueMap valueMap = item.getValueMap();
                inheritCollection = getBooleanValue(valueMap, PROPERTY_CONFIG_COLLECTION_INHERIT, config.configCollectionInheritancePropertyNames());
                inheritProperties = getBooleanValue(valueMap, PROPERTY_CONFIG_PROPERTY_INHERIT, config.configPropertyInheritancePropertyNames());
                inherit = inheritCollection || inheritProperties;
                if (!inherit) {
                    break;
                }
            }
            else {
                if (logger.isTraceEnabled()) {
                    logger.trace("- no item '{}' under config '{}'", name, path);
                }
            }
            idx++;
        }

        if (logger.isTraceEnabled()) {
            logger.trace("- final list has {} items", result.size());
        }

        // replace config resources with wrappers with merged properties if property inheritance was applied
        if (propertyInheritanceApplied) {
            List<Resource> transformedResult = new ArrayList<>();
            for (Map.Entry<String, Resource> entry : result.entrySet()) {
                Map<String,Object> configValueMap = configValueMaps.get(entry.getKey());
                if (configValueMap instanceof ValueMap) {
                    transformedResult.add(entry.getValue());
                }
                else {
                    transformedResult.add(new ConfigurationResourceWrapper(entry.getValue(), new ValueMapDecorator(configValueMap)));
                }
            }
            return transformedResult;
        }
        else {
            return result.values();
        }
    }

    private boolean isValidResourceCollectionItem(Resource resource) {
        // do not include jcr:content nodes in resource collection list
        return !StringUtils.equals(resource.getName(), "jcr:content");
    }

    private boolean getBooleanValue(final ValueMap valueMap, final String key, final String[] additionalKeys) {
        Boolean result = valueMap.get(key, Boolean.class);
        if ( result == null && !ArrayUtils.isEmpty(additionalKeys) ) {
            for(final String name : additionalKeys) {
                result = valueMap.get(name, Boolean.class);
                if ( result != null ) {
                    break;
                }
            }
        }
        return result == null ? false : result.booleanValue();
    }

    @Override
    public String getResourcePath(Resource contentResource, String bucketName, String configName) {
        if (!isEnabledAndParamsValid(contentResource, bucketName, configName)) {
            return null;
        }
        String name = bucketName + "/" + configName;

        Iterator<String> configPaths = this.findConfigRefs(contentResource, bucketName);
        if (configPaths.hasNext()) {
            String configPath = buildResourcePath(configPaths.next(), name);
            logger.debug("Building configuration path {} for resource {}: {}", name, contentResource.getPath(), configPath);
            return configPath;
        }
        else {
            logger.debug("No configuration path {}  foundfor resource {}.", name, contentResource.getPath());
            return null;
        }
    }

    @Override
    public String getResourceCollectionParentPath(Resource contentResource, String bucketName, String configName) {
        if (!isEnabledAndParamsValid(contentResource, bucketName, configName)) {
            return null;
        }
        String name = bucketName + "/" + configName;

        Iterator<String> configPaths = this.findConfigRefs(contentResource, bucketName);
        if (configPaths.hasNext()) {
            String configPath = buildResourcePath(configPaths.next(), name);
            logger.debug("Building configuration collection parent path {} for resource {}: {}", name, contentResource.getPath(), configPath);
            return configPath;
        }
        else {
            logger.debug("No configuration collection parent path {}  foundfor resource {}.", name, contentResource.getPath());
            return null;
        }
    }

}
