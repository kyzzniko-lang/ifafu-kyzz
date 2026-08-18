import frida, sys, time

device = frida.get_device_manager().add_remote_device('127.0.0.1:29666')

print("Spawn campus app...")
pid = device.spawn(['net.crigh.cgsport'])
session = device.attach(pid)

hook_code = """
setTimeout(function() {
    try {
        Java.perform(function() {
            try {
                var SettingsGlobal = Java.use('android.provider.Settings$Global');
                SettingsGlobal.getString.overload('android.content.ContentResolver', 'java.lang.String').implementation = function(resolver, name) {
                    if (name === 'mock_location_app') return null;
                    return this.getString(resolver, name);
                };
                console.log('[HOOK] Mock detection blocked - active');
            } catch(e) { console.log('[HOOK] Java error: ' + e); }
        });
    } catch(e) { console.log('[HOOK] Error: ' + e); }
}, 500);
"""

script = session.create_script(hook_code)
script.on('message', lambda msg, data: print(f"[FRIDA] {msg.get('payload', msg) if isinstance(msg, dict) else msg}"))
script.load()

device.resume(pid)
print("HOOK ACTIVE - app running, mock detection blocked")
sys.stdout.flush()

try:
    while True:
        time.sleep(3)
except KeyboardInterrupt:
    print("Stopping...")
    session.detach()
