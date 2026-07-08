package dev.tlang.modules;

import java.util.Map;

/**
 * Interface representing a native module registered in TLang.
 */
public interface NativeModule {
    Map<String, Object> getExports();
}
