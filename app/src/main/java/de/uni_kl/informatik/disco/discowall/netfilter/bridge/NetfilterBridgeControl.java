package de.uni_kl.informatik.disco.discowall.netfilter.bridge;

import android.util.Log;

import java.io.IOException;

import de.uni_kl.informatik.disco.discowall.AppManagement;
import de.uni_kl.informatik.disco.discowall.firewallService.Firewall;
import de.uni_kl.informatik.disco.discowall.netfilter.IptableConstants;
import de.uni_kl.informatik.disco.discowall.netfilter.IptablesControl;
import de.uni_kl.informatik.disco.discowall.netfilter.NetfilterExceptions;
import de.uni_kl.informatik.disco.discowall.utils.shell.ShellExecuteExceptions;

public class NetfilterBridgeControl {
    private static final String LOG_TAG = NetfilterBridgeControl.class.getSimpleName();
    private final AppManagement appManagement;
    private final NetfilterBridgeCommunicator.EventsHandler bridgeEventsHandler;

    private final NetfilterBridgeIptablesHandler iptablesHandler;
    private final NetfilterBridgeBinaryHandler bridgeBinaryHandler;
    private NetfilterBridgeCommunicator bridgeCommunicator;
    private final int bridgeCommunicationPort;

    public NetfilterBridgeControl(NetfilterBridgeCommunicator.EventsHandler bridgeEventsHandler, AppManagement appManagement, int bridgeCommunicationPort) throws NetfilterExceptions.NetfilterBridgeDeploymentException, ShellExecuteExceptions.ReturnValueException, ShellExecuteExceptions.CallException, IOException {
        Log.d(LOG_TAG, "initializing NetfilterBridgeControl...");

        this.bridgeEventsHandler = bridgeEventsHandler;
        this.bridgeCommunicationPort = bridgeCommunicationPort;

        this.appManagement = appManagement;
        this.bridgeBinaryHandler = new NetfilterBridgeBinaryHandler(appManagement);
        this.iptablesHandler = new NetfilterBridgeIptablesHandler(bridgeCommunicationPort);

        // -----------------------------------------------------------------------------------------------------------
        // Connect to bridge
        // -----------------------------------------------------------------------------------------------------------

        Log.d(LOG_TAG, "connecting to netfilter-bridge...");
        Log.v(LOG_TAG, "netfilter bridge is deployed: " + bridgeBinaryHandler.isDeployed());

        if (!bridgeBinaryHandler.isDeployed()) {
            bridgeBinaryHandler.deploy();

            // assert deployment
            if (!bridgeBinaryHandler.isDeployed())
                Log.e(LOG_TAG, "error deploying netfilter bridge. File has NOT been deployed!");
        }

        // Creating required iptable-rules: ESPECIALLY the rule-exceptions for the bridge-android-communication via tcp
        Log.d(LOG_TAG, "adding static iptable-rules required for bridge-android communication");
        iptablesHandler.rulesEnableAll();

        Log.d(LOG_TAG, "starting netfilter bridge communicator as listening server...");
        bridgeCommunicator = new NetfilterBridgeCommunicator(bridgeEventsHandler, bridgeCommunicationPort);
        Log.d(LOG_TAG, "listening on port: " + bridgeCommunicationPort);

        Log.d(LOG_TAG, "killing all possibly running netfilter bridge instances...");
        bridgeBinaryHandler.killAllInstances();

        // Disabled for DEBUGGING - using external instance within shell
        Log.d(LOG_TAG, "executing netfilter bridge binary...");
        bridgeBinaryHandler.start(bridgeCommunicationPort);

        Log.d(LOG_TAG, "netfilter-bridge connected.");
    }

    public boolean isBridgeConnected() {
        if (bridgeCommunicator == null)
            return false;
        else
            return bridgeCommunicator.isConnected() && bridgeBinaryHandler.isProcessRunning();
    }

    public void disconnectBridge() throws IOException, ShellExecuteExceptions.CallException, ShellExecuteExceptions.ReturnValueException {
        Log.d(LOG_TAG, "disconnecting netfilter-bridge-communicator");

        // Removing any IPTABLE-rules. Has to be done first, so that no packages are "stuck" within the NFQUEUE-chain.
        Log.d(LOG_TAG, "removing all static iptable-rules");
        iptablesHandler.rulesDisableAll(true);

        if (bridgeCommunicator == null) {
            Log.v(LOG_TAG, "bridge-communicator has never been connected - nothing to disconnect.");
            return;
        }

        bridgeCommunicator.disconnect();

        Log.d(LOG_TAG, "killing all possibly running netfilter bridge instances...");
        bridgeBinaryHandler.killAllInstances();
    }

}