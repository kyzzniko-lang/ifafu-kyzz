// Hook mock_location_app detection for 创高体育
'use strict';

// Hook 1: Settings.Global.getString - return null for mock_location_app
var SettingsGlobal = Java.use('android.provider.Settings$Global');
SettingsGlobal.getString.overload('android.content.ContentResolver', 'java.lang.String').implementation = function (resolver, name) {
    if (name === 'mock_location_app') {
        console.log('[HOOK] Blocked mock_location_app query');
        return null;
    }
    return this.getString(resolver, name);
};

// Hook 2: Settings.Secure.getString - block related checks
var SettingsSecure = Java.use('android.provider.Settings$Secure');
SettingsSecure.getString.overload('android.content.ContentResolver', 'java.lang.String').implementation = function (resolver, name) {
    if (name && name.indexOf('mock') !== -1) {
        console.log('[HOOK] Blocked secure mock check:', name);
        return null;
    }
    return this.getString(resolver, name);
};

// Hook 3: Settings.Global.getInt - block int-based mock checks
SettingsGlobal.getInt.overload('android.content.ContentResolver', 'java.lang.String', 'int').implementation = function (resolver, name, def) {
    if (name && name.indexOf('mock') !== -1) {
        console.log('[HOOK] Blocked getInt:', name);
        return def;
    }
    return this.getInt(resolver, name, def);
};

// Hook 4: Settings.Global.getInt (no default)
SettingsGlobal.getInt.overload('android.content.ContentResolver', 'java.lang.String').implementation = function (resolver, name) {
    if (name && name.indexOf('mock') !== -1) {
        console.log('[HOOK] Blocked getInt:', name);
        return 0;
    }
    return this.getInt(resolver, name);
};

// Hook 5: LocationManager.isFromMockProvider - always false
var Location = Java.use('android.location.Location');
Location.isFromMockProvider.implementation = function () {
    return false;
};

// Hook 6: Log any Settings queries from the app for debugging
var ContentResolver = Java.use('android.content.ContentResolver');
ContentResolver.acquireContentProviderClient.overload('java.lang.String').implementation = function (name) {
    if (name && name.indexOf('settings') !== -1) {
        var bt = Java.use('android.util.Log').getStackTraceString(Java.use('java.lang.Exception').$new());
        if (bt.indexOf('cgsport') !== -1 || bt.indexOf('crigh') !== -1) {
            console.log('[HOOK] Settings query from campus app:', name);
        }
    }
    return this.acquireContentProviderClient(name);
};

console.log('[HOOK] Mock detection bypass scripts loaded');
