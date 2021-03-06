package org.netbeans.gradle.project.properties.global;

import java.util.prefs.PreferenceChangeListener;
import java.util.prefs.Preferences;
import org.jtrim.event.ListenerRef;
import org.netbeans.gradle.project.NbGradleProjectFactory;
import org.netbeans.gradle.project.api.event.NbListenerRefs;
import org.openide.util.NbPreferences;

public enum NbGlobalPreference implements BasicPreference {
    DEFAULT;

    private static Preferences getPreferences() {
        return NbPreferences.forModule(NbGradleProjectFactory.class);
    }

    @Override
    public void put(String key, String value) {
        getPreferences().put(key, value);
    }

    @Override
    public void remove(String key) {
        getPreferences().remove(key);
    }

    @Override
    public String get(String key) {
        return getPreferences().get(key, null);
    }

    @Override
    public ListenerRef addPreferenceChangeListener(final PreferenceChangeListener pcl) {
        final Preferences preferences = getPreferences();
        // To be super safe, we could wrap the listener
        preferences.addPreferenceChangeListener(pcl);
        return NbListenerRefs.fromRunnable(new Runnable() {
            @Override
            public void run() {
                preferences.removePreferenceChangeListener(pcl);
            }
        });
    }
}
