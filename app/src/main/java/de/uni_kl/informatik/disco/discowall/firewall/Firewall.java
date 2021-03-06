package de.uni_kl.informatik.disco.discowall.firewall;

import android.content.Context;
import android.util.Log;

import java.util.LinkedList;
import java.util.List;

import de.uni_kl.informatik.disco.discowall.firewall.helpers.FirewallPolicyManager;
import de.uni_kl.informatik.disco.discowall.firewall.helpers.FirewallRulesManager;
import de.uni_kl.informatik.disco.discowall.firewall.helpers.WatchedAppsManager;
import de.uni_kl.informatik.disco.discowall.firewall.packageFilter.FirewallPackageFilter;
import de.uni_kl.informatik.disco.discowall.firewall.rules.FirewallIptableRulesHandler;
import de.uni_kl.informatik.disco.discowall.firewall.rules.FirewallRuleExceptions;
import de.uni_kl.informatik.disco.discowall.firewall.rules.FirewallRules;
import de.uni_kl.informatik.disco.discowall.firewall.subsystems.SubsystemPendingPackagesManager;
import de.uni_kl.informatik.disco.discowall.firewall.subsystems.SubsystemRulesManager;
import de.uni_kl.informatik.disco.discowall.firewall.subsystems.SubsystemWatchedApps;
import de.uni_kl.informatik.disco.discowall.firewall.util.FirewallRuledApp;
import de.uni_kl.informatik.disco.discowall.gui.dialogs.ErrorDialog;
import de.uni_kl.informatik.disco.discowall.netfilter.bridge.NetfilterBridgeCommunicator;
import de.uni_kl.informatik.disco.discowall.netfilter.bridge.NetfilterBridgeControl;
import de.uni_kl.informatik.disco.discowall.netfilter.bridge.NetfilterBridgeIptablesHandler;
import de.uni_kl.informatik.disco.discowall.netfilter.bridge.NetfilterFirewallRulesHandler;
import de.uni_kl.informatik.disco.discowall.netfilter.iptables.IptablesControl;
import de.uni_kl.informatik.disco.discowall.packages.ConnectionManager;
import de.uni_kl.informatik.disco.discowall.packages.Connections;
import de.uni_kl.informatik.disco.discowall.packages.Packages;
import de.uni_kl.informatik.disco.discowall.utils.NetworkInterfaceHelper;
import de.uni_kl.informatik.disco.discowall.utils.apps.AppUidGroup;
import de.uni_kl.informatik.disco.discowall.utils.ressources.DiscoWallSettings;
import de.uni_kl.informatik.disco.discowall.utils.shell.ShellExecuteExceptions;

public class Firewall implements NetfilterBridgeCommunicator.BridgeEventsHandler, NetfilterBridgeCommunicator.PackageReceivedHandler {
    private static final String LOG_TAG = Firewall.class.getSimpleName();

    // ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
    //   Types, Interfaces & Listeners
    // ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
    public class FirewallSubsystems {
        public final SubsystemWatchedApps watchedApps = Firewall.this.subsystemWatchedApps;
        public final SubsystemRulesManager rulesManager = Firewall.this.subsystemRulesManager;
        public final SubsystemPendingPackagesManager pendingActionsManager = packageFilter;
    }

    /**
     * Listener used so that the busy-dialog may show relevant data to the user, while the firewall is being enabled.
     *
     * @see de.uni_kl.informatik.disco.discowall.firewall.Firewall.FirewallDisableProgressListener
     */
    public static interface FirewallEnableProgressListener extends IptablesControl.IptablesCommandListener {
        void onWatchedAppsBeforeRestore(List<AppUidGroup> watchedApps);
        void onWatchedAppsRestoreApp(AppUidGroup watchedApp, int appIndex);

        void onFirewallPolicyBeforeApplyPolicy(FirewallPolicyManager.FirewallPolicy policy);
        void onFirewallBeforeRestoreRules(int totalRulesCount);
//        void onFirewallBeforeRestoreRulesBeforeLoadXML();
        void onFirewallRestoreRule(FirewallRules.IFirewallRule rule, AppUidGroup watchedApp);
    }

    /**
     * Listener used so that the busy-dialog may show relevant data to the user, while the firewall is being disabled.
     *
     * @see de.uni_kl.informatik.disco.discowall.firewall.Firewall.FirewallEnableProgressListener
     */
    public static interface FirewallDisableProgressListener extends IptablesControl.IptablesCommandListener {
        // No extra callbacks but the inherrited Iptables-Command-Callbacks
    }

    /**
     * Listener implemented by the {@link FirewallService} in order to update the <b>notification-icon</b> as the firewall-state changes.
     */
    public static interface FirewallStateListener {
        void onFirewallStateChanged(FirewallState state, FirewallPolicyManager.FirewallPolicy policy);
        void onFirewallPolicyChanged(FirewallPolicyManager.FirewallPolicy policy);
    }

    //---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
    public static enum FirewallState { RUNNING, PAUSED, STOPPED;}
    private FirewallState firewallState;

    private final ConnectionManager connectionManager = new ConnectionManager();
    private final NetworkInterfaceHelper networkInterfaceHelper = new NetworkInterfaceHelper();
    private final FirewallIptableRulesHandler iptableRulesManager = NetfilterFirewallRulesHandler.instance;
    private final FirewallPackageFilter packageFilter;

    // Helpers:
    private final FirewallPolicyManager policyManager = new FirewallPolicyManager(NetfilterFirewallRulesHandler.instance);
    private final FirewallRulesManager firewallRulesManager = new FirewallRulesManager();
    private final WatchedAppsManager watchedAppsManager;

    // Firewall-Service-Connection:
    private final Context firewallServiceContext;
    private FirewallStateListener firewallStateListener;

    private NetfilterBridgeControl control;
//    private DnsCacheControl dnsCacheControl;

    // Firewall Subsytems:
    public final FirewallSubsystems subsystem;
    private final SubsystemWatchedApps subsystemWatchedApps;
    private final SubsystemRulesManager subsystemRulesManager;

    public Firewall(FirewallService firewallServiceContext) {
        Log.i(LOG_TAG, "initializing firewall service...");

        this.firewallServiceContext = firewallServiceContext;
        this.firewallState = FirewallState.STOPPED;

        // Helpers:
        this.watchedAppsManager = new WatchedAppsManager(firewallServiceContext);
        this.packageFilter = new FirewallPackageFilter(firewallServiceContext, policyManager, firewallRulesManager, watchedAppsManager);

        // Subsystems:
        this.subsystemWatchedApps = new SubsystemWatchedApps(this, firewallServiceContext, iptableRulesManager, watchedAppsManager);
        this.subsystemRulesManager = new SubsystemRulesManager(this, firewallServiceContext, firewallRulesManager, watchedAppsManager);
        this.subsystem = new FirewallSubsystems();

        // Load stored rules:
        loadStoredRulesFromStorage();

        Log.i(LOG_TAG, "firewall service running.");
    }

    /**
     * Used by FirewallService to show state in notification-bar etc.
     * @param stateListener
     */
    void setFirewallStateListener(FirewallStateListener stateListener) {
        this.firewallStateListener = stateListener;
    }

    FirewallStateListener getStateListener() {
        return firewallStateListener;
    }

    private void onFirewallStateChanged(FirewallState state) {
        this.firewallState = state;
        Log.d(LOG_TAG, "Firewall state changed: " + state);

        if (firewallStateListener != null)
            firewallStateListener.onFirewallStateChanged(state, getFirewallPolicy());
    }

    public void enableFirewall(int port) throws FirewallExceptions.FirewallException {
        enableFirewall(port, null);
    }

    public void enableFirewall(int port, FirewallEnableProgressListener progressListener) throws FirewallExceptions.FirewallException {
        Log.i(LOG_TAG, "starting firewall...");

        boolean alreadyRunnig = isFirewallRunning();
        Log.v(LOG_TAG, "check if firewall already running: " + alreadyRunnig);

        if (alreadyRunnig)
        {
            Log.i(LOG_TAG, "firewall already running. nothing to do.");
        } else {

            // Commandlistener is only temporarily being set
            if (progressListener != null)
                IptablesControl.setCommandListener(progressListener);

            // starting netfilter bridge - i.e. the "firewall core"
            try {
                boolean startNetfilterBridgeInstance = DiscoWallSettings.getInstance().isNfqueueBridgeAutomaticallyStartLocalInstance(firewallServiceContext);
                control = new NetfilterBridgeControl(startNetfilterBridgeInstance, this, this, firewallServiceContext, port);
            } catch(Exception e) {
                IptablesControl.setCommandListener(null); // removing command-listener
                throw new FirewallExceptions.FirewallException("Error initializing firewall: " + e.getMessage(), e);
            }

            // removing iptables-command-listener.
            IptablesControl.setCommandListener(null);

            // starting the dns cache for sniffing the dns-resolutions
//            dnsCacheControl = new DnsCacheControl(DiscoWallConstants.DnsCache.dnsCachePort);

            Log.d(LOG_TAG, "firewall engine running.");
            onFirewallStateChanged(FirewallState.RUNNING); // has to be called here, so that all following algorithms get the correct firewall-running-state

            // Start watching apps which have been watched before
            Log.d(LOG_TAG, "restoring forwarding-rules for watched apps...");
            {
                LinkedList<AppUidGroup> watchedApps = subsystemWatchedApps.getWatchedAppGroups();

                // reporting progress to listener
                if (progressListener != null)
                    progressListener.onWatchedAppsBeforeRestore(watchedApps);

                int appIndex = 0;
                for (AppUidGroup watchedApp : watchedApps) {
                    if (progressListener != null)
                        progressListener.onWatchedAppsRestoreApp(watchedApp, appIndex++); // reporting progress to listener

                    subsystemWatchedApps.setAppGroupWatched(watchedApp, true);
                }
            }

            Log.d(LOG_TAG, "restoring saved rules...");
            {
                // reporting progress to listener
                if (progressListener != null)
                    progressListener.onFirewallBeforeRestoreRules(subsystemRulesManager.getAllRules().size());

                Log.i(LOG_TAG, "enabling iptables forwarding support...");
                try {
                    NetfilterFirewallRulesHandler.instance.enableIptablesRedirection();
                } catch (ShellExecuteExceptions.ShellExecuteException e) {
                    Log.e(LOG_TAG, "Error writing rule to iptables: " + e.getMessage(), e);
                }

                boolean writeInteractiveRulesToIptables = DiscoWallSettings.getInstance().isWriteInteractiveRulesToIptables(firewallServiceContext);

                for(AppUidGroup installedAppGroup : watchedAppsManager.getInstalledAppGroups()) { // Note that ALL rules are being restored - even though the app might not be watched/monitored at the moment
                    for(FirewallRules.IFirewallRule rule : subsystemRulesManager.getRules(installedAppGroup)) {
                        // reporting progress to listener
                        if (progressListener != null)
                            progressListener.onFirewallRestoreRule(rule, installedAppGroup);

                        try {
                            // interactive-rules are only written to iptables, if enabled in settings:
                            if (rule instanceof FirewallRules.IFirewallPolicyRule) {
                                if (!writeInteractiveRulesToIptables)
                                    continue;
                            }

                            rule.addToIptables();
                        } catch (Exception e) {
                            Log.e(LOG_TAG, "Error writing rule to iptables: " + e.getMessage(), e);
                        }
                    }
                }
            }

            Log.d(LOG_TAG, "restoring firewall-policy...");
            {
                FirewallPolicyManager.FirewallPolicy policy = DiscoWallSettings.getInstance().getFirewallPolicy(firewallServiceContext);

                // reporting progress to listener
                if (progressListener != null)
                    progressListener.onFirewallPolicyBeforeApplyPolicy(policy);

                // Apply policy:
                policyManager.setFirewallPolicy(policy, true);
            }

            Log.i(LOG_TAG, "firewall started.");
        }
    }

    public void loadStoredRulesFromStorage() {
        Log.i(LOG_TAG, "loading all stored rules from app storage...");

        try {
            for(FirewallRuledApp ruledAppWithLoadedRules : subsystemRulesManager.loadAllRulesFromAppStorage()) {
                for(FirewallRules.IFirewallRule rule : ruledAppWithLoadedRules.getRules()) {
                    try {
                        subsystemRulesManager.addRule(rule);
                    } catch (FirewallRuleExceptions.DuplicateRuleException e) {
                        Log.e(LOG_TAG, "Trying to import rule which already exists. Rule will not be imported: " + e.getMessage(), e);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error while loading stored rules from XML: " + e.getMessage(), e);
        }
    }

    public void disableFirewall() throws FirewallExceptions.FirewallException {
        disableFirewall(null);
    }

    public void disableFirewall(FirewallDisableProgressListener progressListener) throws FirewallExceptions.FirewallException {
        Log.i(LOG_TAG, "disabling firewall...");

        if (control == null) {
            Log.i(LOG_TAG, "firewall already disabled. nothing to do.");
            onFirewallStateChanged(FirewallState.STOPPED); // state will be broadcasted even if the firewall is already stopped.
            return;
        }

        // I will try disconnecting the bridge - even if the communication itself is already down.
        // This is being done to make sure the user can deactivate the firewall even in an unexpected/erroneous state.

        // Disable iptables hooking-rules, so that no package will be sent to netfilter-bridge binary
        Log.v(LOG_TAG, "disconnecting bridge");

        if (progressListener != null)
            IptablesControl.setCommandListener(progressListener);

        try {
            control.disconnectBridge();
        } catch (Exception e) {
            IptablesControl.setCommandListener(null); // remove temporary listener
            throw new FirewallExceptions.FirewallException("Error disconnecting netfilter-bridge: " + e.getMessage(), e);
        }

        control = null;
        IptablesControl.setCommandListener(null); // remove temporary listener

        Log.i(LOG_TAG, "firewall disabled.");
        onFirewallStateChanged(FirewallState.STOPPED);
    }

    public FirewallState getFirewallState() {
        return firewallState;

        /* It is important to buffer the state in a variable,
         * because fast state-queries immediately after changing the state (happened when switching from DISABLED to RUNNING)
         * may return the old state.
         *
         * This is due to the time it takes for:
         *  - iptable-changes to be reflected by iptables
         *  - ports to connect/disconnect
         * etc.
         *
         * ==> Buffering the state removes the problem and removes the possibility of causing exceptions on query.
         */
    }

    public boolean isFirewallRunning() {
        return firewallState == FirewallState.RUNNING;
    }

    public boolean isFirewallPaused() {
        return firewallState == FirewallState.PAUSED;
    }

    public boolean isFirewallStopped() {
        return firewallState == FirewallState.STOPPED;
    }

    /**
     * Will add/remove the iptable-rules which forward the packages into the firewall main-chain.
     * Removing those rules will circumvent the entire firewall functionality.
     * @param paused
     */
    public void setFirewallPaused(boolean paused) throws ShellExecuteExceptions.CallException, ShellExecuteExceptions.ReturnValueException, FirewallExceptions.FirewallInvalidStateException {
        if (!isFirewallRunning()) {
            Log.e(LOG_TAG, "Firewall is not enabled - cannot pause/unpause the firewall");
            throw new FirewallExceptions.FirewallInvalidStateException("Firewall needs to be running in order to pause/unpause it.", FirewallState.STOPPED);
        }

        if (paused)
            Log.v(LOG_TAG, "Changing firewall state to paused...");
        else
            Log.v(LOG_TAG, "Changing firewall state to running...");

        iptableRulesManager.setMainChainJumpsEnabled(!paused);

        if (paused) {
            Log.d(LOG_TAG, "new firewall state: paused");
            onFirewallStateChanged(FirewallState.PAUSED);
        } else {
            Log.d(LOG_TAG, "new firewall state: running");
            onFirewallStateChanged(FirewallState.RUNNING);
        }
    }

//    private void assertFirewallRunning() {
//        if (!isFirewallRunning())
//            throw new FirewallExceptions.FirewallInvalidStateException("Firewall needs to be running to perform specified action.", FirewallState.STOPPED);
//    }

    public FirewallPolicyManager.FirewallPolicy getFirewallPolicy() {
        return policyManager.getFirewallPolicy();
    }

    public void setFirewallPolicy(FirewallPolicyManager.FirewallPolicy newRulesPolicy) throws FirewallExceptions.FirewallException {
        policyManager.setFirewallPolicy(newRulesPolicy, !isFirewallStopped());

        if (firewallStateListener != null)
            firewallStateListener.onFirewallPolicyChanged(newRulesPolicy);
    }

    @Override
    public void onPackageReceived(Packages.TransportLayerPackage tlPackage, NetfilterBridgeCommunicator.PackageActionCallback actionCallback) {
        // Find device-name for package:
        if (tlPackage.getInputDeviceIndex() >= 0) {
            tlPackage.setNetworkInterface(networkInterfaceHelper.getPackageInterfaceById(tlPackage.getInputDeviceIndex()));
        } else if (tlPackage.getOutputDeviceIndex() >= 0) {
            tlPackage.setNetworkInterface(networkInterfaceHelper.getPackageInterfaceById(tlPackage.getOutputDeviceIndex()));
        }

        // Store user-id within package
        tlPackage.setUserId(tlPackage.getMark() - NetfilterBridgeIptablesHandler.PACKAGE_UID_MARK_OFFSET);

        Connections.Connection connection;

        if (tlPackage instanceof Packages.TcpPackage) {
            Packages.TcpPackage tcpPackage = (Packages.TcpPackage) tlPackage;
            connection = connectionManager.getTcpConnection(tcpPackage);
        } else if (tlPackage instanceof Packages.UdpPackage) {
            Packages.UdpPackage udpPackage = (Packages.UdpPackage) tlPackage;
            connection = connectionManager.getUdpConnection(udpPackage);
        } else {
            Log.e(LOG_TAG, "No handler package-protocol implemented! Package is: " + tlPackage);
            return;
        }

        connection.update(tlPackage);
        Log.v(LOG_TAG, "Connection: " + connection);

        packageFilter.decidePackageAccepted(tlPackage, connection, actionCallback);
    }

    public FirewallRuledApp getRuledApp(AppUidGroup group) {
        boolean isMonitored = subsystemWatchedApps.isAppWatched(group);
        LinkedList<FirewallRules.IFirewallRule> rules = subsystemRulesManager.getRules(group);

        return new FirewallRuledApp(group, rules, isMonitored);
    }

    public LinkedList<FirewallRuledApp> getRuledApps() {
        LinkedList<FirewallRuledApp> ruledApps = new LinkedList<>();

        for(AppUidGroup group : subsystemWatchedApps.getInstalledAppGroups()) {
            ruledApps.add(getRuledApp(group));
        }

        return ruledApps;
    }

    @Override
    public void onInternalERROR(String message, Exception e) {
        ErrorDialog.showError(firewallServiceContext, "DiscoWall Internal Error", "Error within package-filtering engine occurred: " + e.getMessage());
    }

    public void DEBUG_TEST(AppUidGroup appUidGroup) {
        // Add rules only if there are none
        if (subsystemRulesManager.getRules(appUidGroup).size() > 0)
            return;

        Log.i("DEBUG", "Adding testing-rules...");

        try {
            subsystemRulesManager.createTransportLayerRule(
                    appUidGroup,
                    new Packages.IpPortPair("localhost", 0),
                    new Packages.IpPortPair("*", 80),
                    FirewallRules.DeviceFilter.WiFi_UMTS,
                    FirewallRules.ProtocolFilter.TCP_UDP,
                    FirewallRules.RulePolicy.ALLOW
            );

            subsystemRulesManager.createTransportLayerRule(
                    appUidGroup,
                    new Packages.IpPortPair("localhost", 13370 + subsystemRulesManager.getAllRules().size()),
                    new Packages.IpPortPair("google.de", 800 + subsystemRulesManager.getAllRules().size()),
                    FirewallRules.DeviceFilter.WIFI,
                    FirewallRules.ProtocolFilter.TCP,
                    FirewallRules.RulePolicy.BLOCK
            );

            subsystemRulesManager.createTransportLayerRule(
                    appUidGroup,
                    new Packages.IpPortPair("localhost", 100 + subsystemRulesManager.getAllRules().size()),
                    new Packages.IpPortPair("google.de", 200 + subsystemRulesManager.getAllRules().size()),
                    FirewallRules.DeviceFilter.WIFI,
                    FirewallRules.ProtocolFilter.TCP,
                    FirewallRules.RulePolicy.INTERACTIVE
            );

            subsystemRulesManager.createTransportLayerRedirectionRule(
                    appUidGroup,
                    new Packages.IpPortPair("localhost", 1337 + subsystemRulesManager.getAllRules().size()),
                    new Packages.IpPortPair("google.de", 80 + subsystemRulesManager.getAllRules().size()),
                    FirewallRules.DeviceFilter.WIFI,
                    FirewallRules.ProtocolFilter.TCP,
                    new Packages.IpPortPair("remote", 42)
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
