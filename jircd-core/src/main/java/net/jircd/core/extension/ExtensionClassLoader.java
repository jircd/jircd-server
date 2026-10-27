/*
 * Copyright 2026 Guillermo Castro
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package net.jircd.core.extension;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * A per-extension classloader (research.md "Extension system" — "Delegation model"): parent-first
 * delegation for {@code net.jircd.protocol.*} and {@code net.jircd.core.*} SPI types (so an
 * extension's {@code Extension}/ {@code ClientSession}/{@code Channel} references are the same
 * classes {@code jircd-core} uses), everything else resolved from the extension's own classpath
 * first — isolating one extension's dependencies from another's.
 */
public final class ExtensionClassLoader extends URLClassLoader {

    private static final String[] PARENT_FIRST_PREFIXES = {
        "net.jircd.protocol.", "net.jircd.core."
    };

    public ExtensionClassLoader(String extensionId, URL[] classpath, ClassLoader parent) {
        super("extension:" + extensionId, classpath, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null && isParentFirst(name)) {
                loaded = getParent().loadClass(name);
            }
            if (loaded == null) {
                try {
                    loaded = findClass(name);
                } catch (ClassNotFoundException e) {
                    loaded = getParent().loadClass(name);
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private static boolean isParentFirst(String name) {
        for (String prefix : PARENT_FIRST_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return name.startsWith("java.") || name.startsWith("javax.");
    }
}
