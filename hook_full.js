'use strict';

Java.perform(function() {
    // Hook: Settings.Global - return null for mock_location_app
    try {
        var SG = Java.use('android.provider.Settings$Global');
        SG.getString.overload('android.content.ContentResolver', 'java.lang.String').implementation = function(r, n) {
            if (n === 'mock_location_app') return null;
            return this.getString(r, n);
        };
        SG.getInt.overload('android.content.ContentResolver', 'java.lang.String', 'int').implementation = function(r, n, d) {
            if (n === 'mock_location_app') return d;
            return this.getInt(r, n, d);
        };
        SG.getInt.overload('android.content.ContentResolver', 'java.lang.String').implementation = function(r, n) {
            if (n === 'mock_location_app') return 0;
            return this.getInt(r, n);
        };
        console.log('[hook] Global done');
    } catch(e) { console.log('[hook] Global err: ' + e); }

    // Hook: Settings.Secure - block location_mode queries
    try {
        var SS = Java.use('android.provider.Settings$Secure');
        SS.getString.overload('android.content.ContentResolver', 'java.lang.String').implementation = function(r, n) {
            if (n === 'mock_location_app') return null;
            if (n === 'location_mode') return '3';
            return this.getString(r, n);
        };
        console.log('[hook] Secure done');
    } catch(e) { console.log('[hook] Secure err: ' + e); }

    console.log('[hook] All hooks active');
});
