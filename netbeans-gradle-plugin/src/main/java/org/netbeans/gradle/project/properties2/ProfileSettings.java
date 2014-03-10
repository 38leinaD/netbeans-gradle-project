package org.netbeans.gradle.project.properties2;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.jtrim.collections.EqualityComparator;
import org.jtrim.concurrent.UpdateTaskExecutor;
import org.jtrim.event.CopyOnTriggerListenerManager;
import org.jtrim.event.EventDispatcher;
import org.jtrim.event.ListenerManager;
import org.jtrim.event.ListenerRef;
import org.jtrim.event.ListenerRegistries;
import org.jtrim.property.MutableProperty;
import org.jtrim.property.PropertyFactory;
import org.jtrim.property.PropertySourceProxy;
import org.jtrim.swing.concurrent.SwingUpdateTaskExecutor;
import org.jtrim.utils.ExceptionHelper;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

public final class ProfileSettings {
    private static final Logger LOGGER = Logger.getLogger(ProfileSettings.class.getName());
    private static final int FILE_STREAM_BUFFER_SIZE = 8 * 1024;
    private static final Set<ConfigPath> ROOT_PATH = Collections.singleton(ConfigPath.ROOT);

    private final ListenerManager<ConfigUpdateListener> configUpdateListeners;
    private final EventDispatcher<ConfigUpdateListener, Collection<ConfigPath>> configUpdateDispatcher;

    private final ReentrantLock configLock;
    private volatile Object configStateKey;
    private ConfigTree.Builder currentConfig;

    public ProfileSettings() {
        this.configLock = new ReentrantLock();
        this.currentConfig = new ConfigTree.Builder();
        this.configUpdateListeners = new CopyOnTriggerListenerManager<>();
        this.configStateKey = new Object();

        this.configUpdateDispatcher = new EventDispatcher<ConfigUpdateListener, Collection<ConfigPath>>() {
            @Override
            public void onEvent(ConfigUpdateListener eventListener, Collection<ConfigPath> arg) {
                eventListener.configUpdated(arg);
            }
        };
    }

    public static boolean isEventThread() {
        return SwingUtilities.isEventDispatchThread();
    }

    ListenerRef addDocumentChangeListener(final Runnable listener) {
        ExceptionHelper.checkNotNullArgument(listener, "listener");

        return configUpdateListeners.registerListener(new ConfigUpdateListener() {
            @Override
            public void configUpdated(Collection<ConfigPath> changedPaths) {
                listener.run();
            }
        });
    }

    private static DocumentBuilder getDocumentBuilder() {
        try {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder();
        } catch (ParserConfigurationException ex) {
            throw new RuntimeException("Cannot create Document builder.", ex);
        }
    }

    private static Document getEmptyDocument() {
        return getDocumentBuilder().newDocument();
    }

    private static Document readXml(InputStream xmlSource) throws IOException, SAXException {
        ExceptionHelper.checkNotNullArgument(xmlSource, "xmlSource");

        return getDocumentBuilder().parse(xmlSource);
    }

    private static Document readXml(Path xmlFile) throws IOException, SAXException {
        ExceptionHelper.checkNotNullArgument(xmlFile, "xmlFile");

        if (!Files.exists(xmlFile)) {
            return getEmptyDocument();
        }

        try (InputStream fileInput = Files.newInputStream(xmlFile);
                InputStream input = new BufferedInputStream(fileInput, FILE_STREAM_BUFFER_SIZE)) {
            return readXml(input);
        }
    }

    public void loadFromFile(Path xmlFile) {
        Document document;
        try {
            document = readXml(xmlFile);
        } catch (IOException | SAXException ex) {
            LOGGER.log(Level.INFO, "Unable to parse XML config file: " + xmlFile, ex);
            return;
        }

        loadFromDocument(document);
    }

    public void loadFromStream(InputStream xmlSource) {
        Document document;
        try {
            document = readXml(xmlSource);
        } catch (IOException | SAXException ex) {
            LOGGER.log(Level.INFO, "Unable to parse XML config file from stream.", ex);
            return;
        }

        loadFromDocument(document);
    }

    private void fireDocumentUpdate(final Collection<ConfigPath> path) {
        configUpdateListeners.onEvent(configUpdateDispatcher, path);
    }

    private Object newConfigState() {
        assert configLock.isHeldByCurrentThread();

        Object newState = new Object();
        configStateKey = newState;
        return newState;
    }

    private void loadFromDocument(final Document document) {
        ExceptionHelper.checkNotNullArgument(document, "document");

        ConfigTree.Builder parsedDocument = ConfigXmlUtils.parseDocument(document);

        configLock.lock();
        try {
            currentConfig = parsedDocument;
            newConfigState();
        } finally {
            configLock.unlock();
        }

        fireDocumentUpdate(ROOT_PATH);
    }

    private static ConfigTree createChildTree(ConfigTree.Builder builer, ConfigPath path) {
        ConfigTree.Builder childBuilder = builer.getDeepChildBuilder(path);
        childBuilder.detachChildTreeBuilders();
        return childBuilder.create();
    }

    private <Value> ValueWithStateKey<Value> withStateKey(Value value) {
        assert configLock.isHeldByCurrentThread();
        return new ValueWithStateKey<>(configStateKey, value);
    }

    private ValueWithStateKey<ConfigTree> getChildConfig(ConfigPath path) {
        configLock.lock();
        try {
            return withStateKey(createChildTree(currentConfig, path));
        } finally {
            configLock.unlock();
        }
    }

    private ValueWithStateKey<ConfigTree> getChildConfig(ConfigPath basePath, ConfigPath[] relPaths) {
        if (relPaths.length == 1) {
            assert relPaths[0].getKeyCount() == 0;

            // Common case
            return getChildConfig(basePath);
        }

        Object resultStateKey;
        ConfigTree.Builder result = new ConfigTree.Builder();
        configLock.lock();
        try {
            resultStateKey = configStateKey;

            ConfigTree.Builder baseBuilder = currentConfig.getDeepChildBuilder(basePath);
            for (ConfigPath relPath: relPaths) {
                ConfigTree childTree = createChildTree(baseBuilder, relPath);
                setChildTree(result, relPath, childTree);
            }
        } finally {
            configLock.unlock();
        }

        return new ValueWithStateKey<>(resultStateKey, result.create());
    }

    public <ValueKey, ValueType> MutableProperty<ValueType> getProperty(
            ConfigPath configPath,
            PropertyDef<ValueKey, ValueType> propertyDef) {
        return getProperty(Collections.singleton(configPath), propertyDef);
    }

    public <ValueKey, ValueType> MutableProperty<ValueType> getProperty(
            Collection<ConfigPath> configPaths,
            PropertyDef<ValueKey, ValueType> propertyDef) {
        ExceptionHelper.checkNotNullArgument(configPaths, "configPaths");
        ExceptionHelper.checkNotNullArgument(propertyDef, "propertyDef");

        return new DomTrackingProperty<>(configPaths, propertyDef);
    }

    private static List<ConfigPath> copyPaths(Collection<ConfigPath> paths) {
        switch (paths.size()) {
            case 0:
                return Collections.emptyList();
            case 1:
                return Collections.singletonList(paths.iterator().next());
            default:
                return Collections.unmodifiableList(new ArrayList<>(paths));
        }
    }

    private static ConfigPath[] removeTopParents(int removeCount, ConfigPath[] paths) {
        if (removeCount == 0) {
            return paths;
        }

        ConfigPath[] result = new ConfigPath[paths.length];
        for (int i = 0; i < result.length; i++) {
            List<String> keys = paths[i].getKeys();
            result[i] = ConfigPath.fromKeys(keys.subList(removeCount, keys.size()));
        }
        return result;
    }

    private static ConfigPath getCommonParent(ConfigPath[] paths) {
        if (paths.length == 1) {
            // Almost every time this path is taken.
            return paths[0];
        }
        if (paths.length == 0) {
            return ConfigPath.ROOT;
        }

        int minLength = paths[0].getKeyCount();
        for (int i = 1; i < paths.length; i++) {
            int keyCount = paths[i].getKeyCount();
            if (keyCount < minLength) minLength = keyCount;
        }

        List<String> result = new LinkedList<>();

        outerLoop:
        for (int keyIndex = 0; keyIndex < minLength; keyIndex++) {
            String key = paths[0].getKeyAt(keyIndex);
            for (int pathIndex = 1; pathIndex < paths.length; pathIndex++) {
                if (!key.equals(paths[pathIndex].getKeyAt(keyIndex))) {
                    break outerLoop;
                }
            }
            result.add(key);
        }

        return ConfigPath.fromKeys(result);
    }

    private static void setChildTree(ConfigTree.Builder builder, ConfigPath path, ConfigTree content) {
        int keyCount = path.getKeyCount();
        assert keyCount > 0;

        ConfigTree.Builder childConfig = builder;
        for (int i = 0; i < keyCount - 1; i++) {
            childConfig = childConfig.getChildBuilder(path.getKeyAt(i));
        }
        childConfig.setChildTree(path.getKeyAt(keyCount - 1), content);
    }

    private <ValueKey> ValueWithStateKey<ValueKey> getValueKeyFromCurrentConfig(
            ConfigPath parent,
            ConfigPath[] relativePaths,
            PropertyKeyEncodingDef<ValueKey> keyEncodingDef) {

        ValueWithStateKey<ConfigTree> parentBasedConfig = getChildConfig(parent, relativePaths);
        return parentBasedConfig.withNewValue(keyEncodingDef.decode(parentBasedConfig.value));
    }

    private static interface ConfigUpdateListener {
        public void configUpdated(Collection<ConfigPath> changedPaths);
    }

    private class DomTrackingProperty<ValueKey, ValueType>
    implements
            MutableProperty<ValueType> {

        private final ConfigPath configParent;
        private final ConfigPath[] configPaths;
        private final ConfigPath[] relativeConfigPaths;
        private final List<ConfigPath> configPathsAsList;

        private final PropertyKeyEncodingDef<ValueKey> keyEncodingDef;
        private final PropertyValueDef<ValueKey, ValueType> valueDef;
        private final EqualityComparator<? super ValueKey> valueKeyEquality;
        private final AtomicReference<ValueWithStateKey<ValueKey>> lastValueKeyRef;

        private final UpdateTaskExecutor eventThread;

        private final PropertySourceProxy<ValueType> source;

        public DomTrackingProperty(
                Collection<ConfigPath> configPaths,
                PropertyDef<ValueKey, ValueType> propertyDef) {

            ExceptionHelper.checkNotNullArgument(configPaths, "configPaths");
            ExceptionHelper.checkNotNullArgument(propertyDef, "propertyDef");

            this.configPathsAsList = copyPaths(configPaths);
            this.configPaths = configPathsAsList.toArray(new ConfigPath[configPathsAsList.size()]);
            this.configParent = getCommonParent(this.configPaths);
            this.relativeConfigPaths = removeTopParents(configParent.getKeyCount(), this.configPaths);

            this.keyEncodingDef = propertyDef.getKeyEncodingDef();
            this.valueDef = propertyDef.getValueDef();
            this.valueKeyEquality = propertyDef.getValueKeyEquality();

            ValueWithStateKey<ValueKey> initialValueKey = getValueKeyFromCurrentConfig(
                    this.configParent,
                    this.relativeConfigPaths,
                    this.keyEncodingDef);
            this.lastValueKeyRef = new AtomicReference<>(initialValueKey);
            this.source = PropertyFactory.proxySource(valueDef.property(initialValueKey.value));

            this.eventThread = new SwingUpdateTaskExecutor(false);

            ExceptionHelper.checkNotNullElements(this.configPaths, "configPaths");
        }

        private void updateConfigFromKey() {
            ValueWithStateKey<ValueKey> valueKey;
            ValueWithStateKey<ValueKey> newValueKey;

            do {
                valueKey = lastValueKeyRef.get();
                newValueKey = updateConfigFromKey(valueKey);
            } while (!lastValueKeyRef.compareAndSet(valueKey, newValueKey));
        }

        private ValueWithStateKey<ValueKey> updateConfigFromKey(ValueWithStateKey<ValueKey> valueKey) {
            // Should only be called by updateConfigFromKey()

            ConfigTree encodedValueKey = keyEncodingDef.encode(valueKey.value);
            Object newState;

            configLock.lock();
            try {
                // TODO: Report unsaved keys.
                int pathCount = relativeConfigPaths.length;
                for (int i = 0; i < pathCount; i++) {
                    ConfigPath relativePath = relativeConfigPaths[i];
                    ConfigPath path = configPaths[i];

                    ConfigTree configTree = encodedValueKey.getDeepChildTree(relativePath);
                    updateConfigAtPath(path, configTree);
                }

                newState = newConfigState();
            } finally {
                configLock.unlock();
            }

            fireDocumentUpdate(configPathsAsList);
            return new ValueWithStateKey<>(newState, valueKey.value);
        }

        private void updateConfigAtPath(ConfigPath path, ConfigTree content) {
            assert configLock.isHeldByCurrentThread();

            if (path.getKeyCount() == 0) {
                currentConfig = new ConfigTree.Builder(content);
            }
            else {
                setChildTree(currentConfig, path, content);
            }
        }

        private ValueWithStateKey<ValueKey> getUpToDateValueKey() {
            ValueWithStateKey<ValueKey> lastValueKey;
            Object currentConfigStateKey;

            while (true) {
                lastValueKey = lastValueKeyRef.get();
                currentConfigStateKey = configStateKey;

                if (currentConfigStateKey == lastValueKey.stateKey) {
                    // It is possible that there was a concurrent configuration
                    // reload but in this case we can't decide if it came before
                    // us or not, so we conveniently declare ourselves as the winner.

                    return lastValueKey;
                }
                else {
                    updateFromConfig();
                }
            }
        }

        @Override
        public void setValue(final ValueType value) {
            ValueWithStateKey<ValueKey> lastValueKey = getUpToDateValueKey();

            ValueKey valueKey = valueDef.getKeyFromValue(value);
            if (updateSource(lastValueKey.withNewValue(valueKey))) {
                updateConfigFromKey();
            }
        }

        @Override
        public ValueType getValue() {
            if (lastValueKeyRef.get().stateKey != configStateKey) {
                updateFromConfig();
            }

            return source.getValue();
        }

        private boolean affectsThis(Collection<ConfigPath> changedPaths) {
            if (changedPaths == configPathsAsList) {
                // This event is comming from us, so we won't update.
                // This is necessary for correctness to avoid infinite loop
                // in updateConfigFromKey()
                return false;
            }

            for (ConfigPath changedPath: changedPaths) {
                for (ConfigPath ourPath: configPaths) {
                    if (changedPath.isParentOfOrEqual(ourPath)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private ValueWithStateKey<ValueKey> getValueKey() {
            return getValueKeyFromCurrentConfig(configParent, relativeConfigPaths, keyEncodingDef);
        }

        private boolean updateSource(ValueWithStateKey<ValueKey> valueKey) {
            ValueWithStateKey<ValueKey> prevValueKey = lastValueKeyRef.getAndSet(valueKey);
            if (valueKeyEquality.equals(prevValueKey.value, valueKey.value)) {
                return false;
            }
            else {
                source.replaceSource(valueDef.property(valueKey.value));
                return true;
            }
        }

        private void updateFromConfig() {
            updateSource(getValueKey());
        }

        @Override
        public ListenerRef addChangeListener(final Runnable listener) {
            ExceptionHelper.checkNotNullArgument(listener, "listener");

            ListenerRef ref1 = configUpdateListeners.registerListener(new ConfigUpdateListener() {
                @Override
                public void configUpdated(Collection<ConfigPath> changedPaths) {
                    if (affectsThis(changedPaths)) {
                        updateFromConfig();
                    }
                }
            });

            ListenerRef ref2 = source.addChangeListener(new Runnable() {
                @Override
                public void run() {
                    eventThread.execute(listener);
                }
            });

            return ListenerRegistries.combineListenerRefs(ref1, ref2);
        }

        @Override
        public String toString() {
            return "Property{" + configPaths + '}';
        }
    }

    private static final class ValueWithStateKey<Value> {
        public final Object stateKey;
        public final Value value;

        public ValueWithStateKey(Object stateKey, Value valueKey) {
            this.stateKey = stateKey;
            this.value = valueKey;
        }

        public <NewValue> ValueWithStateKey<NewValue> withNewValue(NewValue newValue) {
            return new ValueWithStateKey<>(stateKey, newValue);
        }
    }
}
