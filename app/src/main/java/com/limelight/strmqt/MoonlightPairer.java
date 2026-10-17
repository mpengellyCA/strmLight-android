package com.limelight.strmqt;

import android.content.Context;

import com.limelight.LimeLog;
import com.limelight.binding.PlatformBinding;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.HostHttpResponseException;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.utils.ServerHelper;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * Moonlight's own pairing, as PcView does it, against the StrmQt host's
 * Sunshine, with the code on screen as the PIN instead of a random one.
 *
 * The computer is added to Moonlight's list under the address and port given
 * (as "Add PC manually" does with address:port), so it is kept apart from any
 * other Sunshine on the same machine: computers are keyed by the uniqueid
 * Sunshine reports, and two Sunshines have two.
 */
public final class MoonlightPairer implements StreamPairer {
    private final Context context;
    private final ComputerManagerService.ComputerManagerBinder binder;

    public MoonlightPairer(Context context, ComputerManagerService.ComputerManagerBinder binder) {
        this.context = context.getApplicationContext();
        this.binder = binder;
    }

    @Override
    public Outcome pair(String host, int port, String pin) throws InterruptedException {
        binder.waitForReady();
        ComputerDetails computer = new ComputerDetails();
        computer.manualAddress = new ComputerDetails.AddressTuple(host, port);
        try {
            if (!binder.addComputerBlocking(computer)) {
                return Outcome.of(Result.UNREACHABLE, host + ":" + port);
            }
        } catch (IllegalArgumentException e) {
            // OkHttp refuses an address it cannot make a URL of.
            return Outcome.of(Result.UNREACHABLE, e.getMessage());
        }
        if (computer.pairState == PairingManager.PairState.PAIRED) {
            return new Outcome(Result.ALREADY_PAIRED, computer.uuid, computer.name, null);
        }

        try {
            NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                    computer.httpsPort, binder.getUniqueId(), computer.serverCert,
                    PlatformBinding.getCryptoProvider(context));
            String serverInfo = http.getServerInfo(true);
            if (http.getPairState(serverInfo) == PairingManager.PairState.PAIRED) {
                return new Outcome(Result.ALREADY_PAIRED, computer.uuid, computer.name, null);
            }

            PairingManager pm = http.getPairingManager();
            // Waits on the host until the PIN is entered there.
            PairingManager.PairState state = pm.pair(serverInfo, pin);
            switch (state) {
                case PAIRED:
                    remember(computer, pm);
                    return new Outcome(Result.PAIRED, computer.uuid, computer.name, null);
                case PIN_WRONG:
                    return Outcome.of(Result.WRONG_PIN, null);
                case ALREADY_IN_PROGRESS:
                    return Outcome.of(Result.SESSION_PENDING, null);
                case FAILED:
                default:
                    return Outcome.of(Result.REFUSED, computer.runningGameId != 0 ? "busy streaming" : null);
            }
        } catch (HostHttpResponseException e) {
            LimeLog.warning("StrmQt stream pairing: " + e.getMessage());
            if (e.getErrorCode() == 409) {
                // "A pairing session with this uniqueid already exists"
                return Outcome.of(Result.SESSION_PENDING, e.getErrorMessage());
            }
            return Outcome.of(Result.REFUSED, e.getErrorMessage());
        } catch (XmlPullParserException e) {
            LimeLog.warning("StrmQt stream pairing: " + e);
            return Outcome.of(Result.REFUSED, e.getMessage());
        } catch (IOException e) {
            LimeLog.warning("StrmQt stream pairing: " + e);
            return Outcome.of(Result.UNREACHABLE, e.getMessage());
        } catch (IllegalArgumentException e) {
            return Outcome.of(Result.UNREACHABLE, e.getMessage());
        }
    }

    /**
     * Pins the certificate pairing gave for later HTTPS use, as PcView does,
     * and polls once more so it reaches Moonlight's database with the pairing.
     */
    private void remember(ComputerDetails paired, PairingManager pm) throws InterruptedException {
        ComputerDetails known = binder.getComputer(paired.uuid);
        if (known != null) {
            known.serverCert = pm.getPairedCert();
        }
        binder.invalidateStateForComputer(paired.uuid);

        ComputerDetails again = new ComputerDetails();
        again.manualAddress = paired.manualAddress;
        again.uuid = paired.uuid;
        if (!binder.addComputerBlocking(again) || again.pairState != PairingManager.PairState.PAIRED) {
            // Paired all the same; the launcher polls again before it streams.
            LimeLog.warning("StrmQt stream pairing: paired, but the check afterwards says " + again.pairState);
        }
    }
}
