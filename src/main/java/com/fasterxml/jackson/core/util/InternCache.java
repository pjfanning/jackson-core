package com.fasterxml.jackson.core.util;

import com.fasterxml.jackson.core.util.internal.DefaultInternCache;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton class that adds a simple first-level cache in front of
 * regular String.intern() functionality. This is done as a minor
 * performance optimization, to avoid calling native intern() method
 * in cases where same String is being interned multiple times.
 */
public interface InternCache {

    static InternCache instance = new DefaultInternCache();



    String intern(String input);
}

