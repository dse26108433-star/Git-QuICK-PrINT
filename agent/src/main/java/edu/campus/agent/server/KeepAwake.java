package edu.campus.agent.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * A PC that is the XeoGo server must not fall asleep by itself while the
 * shop is open: students could not reach it, and nothing would print.
 *
 * (During a load test the test laptop was asleep for four hours in the middle
 * of 400 orders. When it woke up the Station carried on and nothing was lost,
 * but nobody would have been served meanwhile. That time it had been put to
 * sleep on purpose; a PC left alone at the counter must not do the same by
 * itself after a quiet quarter of an hour.)
 *
 * While the server runs, Windows is asked the proper way not to sleep from
 * being idle: an "execution state" request, the same thing a video player
 * uses. It changes no setting, works on mains and on battery, and is gone the
 * moment the Station stops, so the PC behaves as before in the evening.
 * Somebody who chooses Sleep, shuts down, or closes a laptop's lid still gets
 * what they asked for: that is theirs to decide.
 *
 * Checked on a real PC by asking Windows (CallNtPowerInformation, system
 * execution state): with the helper running the request is in force, with
 * the helper stopped by hand it is gone, and LocalStack starts it again.
 *
 * Java cannot ask Windows this directly; a small hidden PowerShell does, and
 * ends by itself when the Station is gone.
 */
final class KeepAwake {

    private static final Logger log = LoggerFactory.getLogger(KeepAwake.class);
    private Process helper;

    synchronized void on() {
        if (helper != null && helper.isAlive()) return;
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        long me = ProcessHandle.current().pid();
        // ES_CONTINUOUS | ES_SYSTEM_REQUIRED, asked again every 30 s; the loop ends when the Station (process $p) is gone
        String script = "$sig = '[DllImport(\"kernel32.dll\")] public static extern uint SetThreadExecutionState(uint esFlags);'\n"
                + "$power = Add-Type -MemberDefinition $sig -Name Power -Namespace XeoGoStation -PassThru\n"
                + "$p = " + me + "\n"
                + "while (Get-Process -Id $p -ErrorAction SilentlyContinue) {\n"
                + "  [void]$power::SetThreadExecutionState([uint32]\"0x80000001\")\n"
                + "  Start-Sleep -Seconds 30\n"
                + "}\n";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        try {
            helper = new ProcessBuilder(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                    "-EncodedCommand", encoded))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("NUL")))
                    .start();
            log.info("Windows is asked not to sleep while the server runs");
        } catch (IOException e) {
            log.warn("Could not ask Windows to stay awake ({}): set Sleep to Never in the Windows power settings", e.getMessage());
        }
    }

    synchronized void off() {
        Process h = helper;
        helper = null;
        if (h != null && h.isAlive()) h.destroy();
    }

    synchronized boolean isOn() {
        return helper != null && helper.isAlive();
    }
}
