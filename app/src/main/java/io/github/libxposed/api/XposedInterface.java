package io.github.libxposed.api;

import android.content.SharedPreferences;

public interface XposedInterface {
    SharedPreferences getRemotePreferences(String name);
}
