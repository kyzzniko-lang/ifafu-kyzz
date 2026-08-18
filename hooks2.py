import frida, time, sys

device = frida.get_device_manager().add_remote_device('127.0.0.1:29666')
pid = device.spawn(['net.crigh.cgsport'])
session = device.attach(pid)

hook = """
Java.perform(function() {
    var SG = Java.use('android.provider.Settings$Global');
    SG.getString.overload('android.content.ContentResolver', 'java.lang.String').implementation = function(r, n) {
        if (n === 'mock_location_app') return null;
        return this.getString(r, n);
    };
    console.log('[OK] hooks active');
});
"""

def on_msg(msg, data):
    print(msg)

script = session.create_script(hook)
script.on('message', on_msg)
script.load()
device.resume(pid)

print("Session alive - press Ctrl+C to stop")
sys.stdout.flush()
while True:
    time.sleep(2)
    try:
        session.ping()
    except:
        print("Session died!")
        break
