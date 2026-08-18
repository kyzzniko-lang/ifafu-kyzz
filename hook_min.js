// Minimal hook - only block mock detection
'use strict';

Java.perform(function() {
    try {
        var SettingsGlobal = Java.use('android.provider.Settings$Global');
        SettingsGlobal.getString.overload('android.content.ContentResolver', 'java.lang.String').implementation = function (resolver, name) {
            if (name === 'mock_location_app') return null;
            return this.getString(resolver, name);
        };
        console.log('[HOOK] Minimal hooks loaded');
    } catch(e) {
        console.log('[HOOK] Error: ' + e);
    }
});
