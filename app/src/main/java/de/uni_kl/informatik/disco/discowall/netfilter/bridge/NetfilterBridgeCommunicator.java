package de.uni_kl.informatik.disco.discowall.netfilter.bridge;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

import de.uni_kl.informatik.disco.discowall.packages.Packages;

public class NetfilterBridgeCommunicator implements Runnable {
    public static interface PackageActionCallback {
//        void acceptPackage(Packages.TransportLayerPackage tlPackage);
//        void blockPackage(Packages.TransportLayerPackage tlPackage);
        void acceptPendingPackage();
        void blockPendingPackage();
    }

    public static interface PackageReceivedHandler {
        /**
         * This method is being called for each received package.
         * <p></p>
         * For any action-decision, except INTERACTIVE, the result can be fetched simply by querying the matching rule (if any), or using the firewall-policy.
         * For INTERACTIVE decisions, however, the user must react by use of a dialog. As android-dialogs are inherintly <b>non-modal</b>, a callback must be used for deciding the response.
         * <p>
         * <b>IMPORTANT: </b> The netfilter-bridge will block until a decision has been reached.
         * @param tlPackage
         * @param actionCallback the callback which lets the PackageReceivedHandler declare his decision.
         * @return
         */
        void onPackageReceived(Packages.TransportLayerPackage tlPackage, PackageActionCallback actionCallback);
    }

    public static interface BridgeEventsHandler {
        /**
         * This method should NEVER be called. It only exists to make debugging simpler, so that errors do not get stuck within LOGCAT only.
         * @param e
         * @return
         */
        void onInternalERROR(String message, Exception e);
    }

    private static final String LOG_TAG = "NfBridgeCommunicator";
    public final int listeningPort;

    // Callbacks & Listeners
    private final BridgeEventsHandler eventsHandler;
    private final PackageReceivedHandler packageReceivedHandler;

    private volatile boolean runCommunicationLoop;
    private volatile boolean connected;

    private ServerSocket serverSocket;
    private Socket clientSocket;
    private IOException connectionException;
    private PrintWriter socketOut;
    private BufferedReader socketIn;

    public NetfilterBridgeCommunicator(PackageReceivedHandler packageReceivedHandler, BridgeEventsHandler eventsHandler, int listeningPort) throws IOException {
        this.packageReceivedHandler = packageReceivedHandler;
        this.eventsHandler = eventsHandler;
        this.listeningPort = listeningPort;

        Log.v(LOG_TAG, "starting listening thread...");

        Log.d(LOG_TAG, "testing availability of listening port: " + listeningPort);
        serverSocket = new ServerSocket(listeningPort);
        serverSocket.close();
        Log.d(LOG_TAG, "Seems to be available. Port will be used: " + listeningPort);

        new Thread(this).start();
    }

    @Override
    public void run() {
        connected = false;

        try {
            Log.v(LOG_TAG, "opening listening port: " + listeningPort);
            serverSocket = new ServerSocket(listeningPort);

            Log.v(LOG_TAG, "waiting for client...");
            clientSocket = serverSocket.accept();

            Log.v(LOG_TAG, "client (netfilter bridge) connected.");
            socketOut = new PrintWriter(clientSocket.getOutputStream(), true);
            socketIn = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            Log.v(LOG_TAG, "IO streams connected.");

            connected = true;

            Log.v(LOG_TAG, "starting communication loop...");
            communicate();
            Log.v(LOG_TAG, "communication loop terminated.");

            Log.v(LOG_TAG, "closing listening port: " + listeningPort);
            serverSocket.close();
        } catch (IOException e) {
            connectionException = e;

            Log.e(LOG_TAG, "netfilter bridge connection closed with exception: " + e.getMessage());
            e.printStackTrace(); // will print to Log.i()
        } finally {
            connected = false;
        }

        if (runCommunicationLoop) {
            Log.d(LOG_TAG, "client disconnected. Reopening socket...");
            run();
        } else {
            Log.d(LOG_TAG, "communication loop terminated.");
        }
    }

    /**
     * Is being called within the thread by {@link #run} and stopy when {@link #runCommunicationLoop} is set to false,
     * or when an exception occurrs.
     */
    private void communicate() throws IOException {
        runCommunicationLoop = true;
        boolean firstMessage = true;

        while (runCommunicationLoop
                && clientSocket.isBound()
                && clientSocket.isConnected()
                && !clientSocket.isClosed()
                && !clientSocket.isInputShutdown()
                && !clientSocket.isOutputShutdown()
               ) {

            String message = socketIn.readLine();
            Log.v(LOG_TAG, "raw message received: " + message);

            if (message == null) {
                Log.d(LOG_TAG, "value 'null' received. Closing connection and waiting for new client.");
                break;
            }

            if (firstMessage) {
                sendMessage(NetfilterBridgeProtocol.Comment.MSG_PREFIX, "DiscoWall App says hello.");
                firstMessage = false;
                continue;
            }

            handleReceivedMessage(message);
        }
    }

    private synchronized void sendMessage(String prefix, String message) {
        Log.v(LOG_TAG, "sendMessage(): " + prefix + message);
        socketOut.println(prefix + message);
        socketOut.flush();
    }

    private void handleReceivedMessage(final String message) {
        if (message.startsWith(NetfilterBridgeProtocol.QueryPackageAction.MSG_PREFIX)) {
            // Example: #Packet.QueryAction##protocol=tcp##ip.src=192.168.178.28##ip.dst=173.194.116.159##tcp.src.port=35251##tcp.dst.port=80#

            Packages.TransportLayerPackage tlPackage;

            try {
                boolean hasInputDeviceInfo = messageContainsValue(message, NetfilterBridgeProtocol.QueryPackageAction.Physical.OPT_VALUE_INPUT_DEVICE);
                boolean hasOutputDeviceInfo = messageContainsValue(message, NetfilterBridgeProtocol.QueryPackageAction.Physical.OPT_VALUE_OUTPUT_DEVICE);

                // Input or Output-Device has to be specified. If not - the package-direction cannot be determined --> ERROR
                if (!(hasInputDeviceInfo || hasOutputDeviceInfo))
                    throw new NetfilterBridgeProtocol.ProtocolValueMissingException(NetfilterBridgeProtocol.QueryPackageAction.Physical.OPT_VALUE_INPUT_DEVICE + "/" +  NetfilterBridgeProtocol.QueryPackageAction.Physical.OPT_VALUE_OUTPUT_DEVICE, message);

                int inputDeviceIndex = -1;
                int outputDeviceIndex = -1;

                if (hasInputDeviceInfo)
                    inputDeviceIndex = extractIntValueFromMessage(message, NetfilterBridgeProtocol.QueryPackageAction.Physical.OPT_VALUE_INPUT_DEVICE);
                if (hasOutputDeviceInfo)
                    outputDeviceIndex = extractIntValueFromMessage(message, NetfilterBridgeProtocol.QueryPackageAction.Physical.OPT_VALUE_OUTPUT_DEVICE);

                String srcIP = extractStringValueFromMessage(message, NetfilterBridgeProtocol.QueryPackageAction.IP.VALUE_SOURCE);
                String dstIP = extractStringValueFromMessage(message, NetfilterBridgeProtocol.QueryPackageAction.IP.VALUE_DESTINATION);

                // Handling of different protocols - currently TCP/UDP
                if (message.contains(NetfilterBridgeProtocol.QueryPackageAction.IP.FLAG_PROTOCOL_TYPE_TCP)) {
                    // Handle TCP Package
                    int srcPort = extractIntValueFromMessage(message, NetfilterBridgeProtocol.QueryPackageAction.TCP.VALUE_SOURCE_PORT);
                    int dstPort = extractIntValueFromMessage(message, NetfilterBridgeProtocol.QueryPackageAction.TCP.VALUE_DESTINATION_PORT);
                    int length = extractIntValueFromMessage(message, NetfilterBridgeProtocol.QueryPackageAction.TCP.VALUE_LENGTH);
                    int checksum = extractIntValueFromMessage(message, NetfilterBridgeProtocol.QueryPackageAction.TCP.VALUE_CHECKSUM);
                    int seqNumber = extractIntValueFromMessage(message, NetfilterBridgeProtocol.QueryPackageAction.TCP.VALUE_SEQUENCE_NUMBER);
                    int ackNumber = extractIntValueFromMessage(message, NetfilterBridgeProtocol.QueryPackageAction.TCP.VALUE_ACK_NUMBER);
                    boolean hasFlagACK = extractBitValueFromMessage(message, NetfilterBridgeProtocol.QueryPackageAction.TCP.VALUE_FLAG_IS_ACK);
                    boolean hasFlagFIN = extractBitValueFromMessage(message, NetfilterBridgeProtocol.QueryPackageAction.TCP.VALUE_FLAG_FIN);
                    boolean hasFlagSYN = extractBitValueFromMessage(message, NetfilterBridgeProtocol.QueryPackageAction.TCP.VALUE_FLAG_SYN);
                    boolean hasFlagPush = extractBitValueFromMessage(message, NetfilterBridgeProtocol.QueryPackageAction.TCP.VALUE_FLAG_PUSH);
                    boolean hasFlagReset = extractBitValueFromMessage(message, NetfilterBridgeProtocol.QueryPackageAction.TCP.VALUE_FLAG_RESET);
                    boolean hasFlagUrgent = extractBitValueFromMessage(message, NetfilterBridgeProtocol.QueryPackageAction.TCP.VALUE_FLAG_URGENT);

                    tlPackage = new Packages.TcpPackage(inputDeviceIndex, outputDeviceIndex, srcIP, dstIP, srcPort, dstPort, length, checksum,
                            seqNumber, ackNumber,
                            hasFlagACK, hasFlagFIN, hasFlagSYN, hasFlagPush, hasFlagReset, hasFlagUrgent
                        );
                } else if (message.contains(NetfilterBridgeProtocol.QueryPackageAction.IP.FLAG_PROTOCOL_TYPE_UDP)) {
                    // Handle UDP  Package
                    int srcPort = extractIntValueFromMessage(message, NetfilterBridgeProtocol.QueryPackageAction.UDP.VALUE_SOURCE_PORT);
                    int dstPort = extractIntValueFromMessage(message, NetfilterBridgeProtocol.QueryPackageAction.UDP.VALUE_DESTINATION_PORT);
                    int length = extractIntValueFromMessage(message, NetfilterBridgeProtocol.QueryPackageAction.UDP.VALUE_LENGTH);
                    int checksum = extractIntValueFromMessage(message, NetfilterBridgeProtocol.QueryPackageAction.UDP.VALUE_CHECKSUM);

                    tlPackage = new Packages.UdpPackage(inputDeviceIndex, outputDeviceIndex, srcIP, dstIP, srcPort, dstPort, length, checksum);
                } else {
                    Log.e(LOG_TAG, "Unknown message format (no transport-layer defined): " + message);
                    NetfilterBridgeProtocol.ProtocolFormatException formatException = new NetfilterBridgeProtocol.ProtocolFormatException("Unknown message format: no transport-layer defined", message);
                    eventsHandler.onInternalERROR(message, formatException);

                    onErroneousPackageReceived();
                    return;
                }

                // ------------------- Decode netfilter- information ---------------------
                tlPackage.setMark(extractIntValueFromMessage(message, NetfilterBridgeProtocol.QueryPackageAction.Netfilter.VALUE_MARK));

            } catch(NetfilterBridgeProtocol.ProtocolException e) {
                Log.e(LOG_TAG, "Error while decoding message: " + message + "\n" + e.getMessage());
                eventsHandler.onInternalERROR("Error while decoding message: " + message + "\n" + e.getMessage(), e);

                onErroneousPackageReceived();
                return;
            }

            Log.v(LOG_TAG, "Decoded package-information: " + tlPackage);

            // React to received package
            onPackageReceived(tlPackage);
        } else if (message.startsWith(NetfilterBridgeProtocol.Comment.MSG_PREFIX)) {
            String comment = message.substring(message.indexOf(NetfilterBridgeProtocol.Comment.MSG_PREFIX));
            Log.v(LOG_TAG, "Comment received: " + comment);
        } else {
            Log.e(LOG_TAG, "Unknown message format: " + message);
        }
    }

    private boolean extractBitValueFromMessage(final String message, final String valueName) throws NetfilterBridgeProtocol.ProtocolValueException {
        int value = extractIntValueFromMessage(message, valueName);
        if (value != 0 && value != 1)
            throw new NetfilterBridgeProtocol.ProtocolValueException(message, value + "", message);

        return value == 1;
    }

    private int extractIntValueFromMessage(final String message, final String valueName) throws NetfilterBridgeProtocol.ProtocolValueMissingException, NetfilterBridgeProtocol.ProtocolValueTypeException {
        String intValueStr = extractStringValueFromMessage(message, valueName);

        try {
            return Integer.parseInt(intValueStr);
        } catch(Exception e) {
            throw new NetfilterBridgeProtocol.ProtocolValueTypeException(Integer.class, intValueStr, message);
        }
    }

    private boolean messageContainsValue(final String message, final String valueName) {
        String valuePrefix = NetfilterBridgeProtocol.VALUE_PREFIX + valueName + NetfilterBridgeProtocol.VALUE_KEY_DELIM;
        String valueSuffix = NetfilterBridgeProtocol.VALUE_SUFFIX;

        if (! (message.contains(valuePrefix) && message.contains(valueSuffix)))
            return false;

        String messageStartingWithValue = message.substring(message.indexOf(valuePrefix) + valuePrefix.length());
        return messageStartingWithValue.contains(valueSuffix); // checking again, in case the suffix is a substring of the prefix
    }

    private String extractStringValueFromMessage(final String message, final String valueName) throws NetfilterBridgeProtocol.ProtocolValueMissingException {
        String valuePrefix = NetfilterBridgeProtocol.VALUE_PREFIX + valueName + NetfilterBridgeProtocol.VALUE_KEY_DELIM;
        String valueSuffix = NetfilterBridgeProtocol.VALUE_SUFFIX;

        if (! (message.contains(valuePrefix) && message.contains(valueSuffix)))
            throw new NetfilterBridgeProtocol.ProtocolValueMissingException(valueName, message);

        String messageStartingWithValue = message.substring(message.indexOf(valuePrefix) + valuePrefix.length());
        if (!messageStartingWithValue.contains(valueSuffix)) // checking again, in case the suffix is a substring of the prefix
            throw new NetfilterBridgeProtocol.ProtocolValueMissingException(valueName, message);

        return messageStartingWithValue.substring(0, messageStartingWithValue.indexOf(valueSuffix));
    }

    private void onPackageReceived(Packages.TransportLayerPackage tlPackage) {
        PackageActionCallbackHandler callbackHandler = new PackageActionCallbackHandler(tlPackage);
        packageReceivedHandler.onPackageReceived(tlPackage, callbackHandler);
    }

    private void onErroneousPackageReceived() {
        Log.e(LOG_TAG, "Accepting erroneous package, so that the netfilter-bridge will not stay blocked while waiting for response.");
        sendPackageQueryResponse(true);
    }

    private synchronized void sendPackageQueryResponse(boolean accept) {
        if (accept)
            sendMessage(NetfilterBridgeProtocol.QueryPackageActionResponse.MSG_PREFIX, NetfilterBridgeProtocol.QueryPackageActionResponse.FLAG_ACCEPT_PACKAGE);
        else
            sendMessage(NetfilterBridgeProtocol.QueryPackageActionResponse.MSG_PREFIX, NetfilterBridgeProtocol.QueryPackageActionResponse.FLAG_DROP_PACKAGE);
    }

    public boolean isConnected() {
        return connected;
    }

    public IOException getConnectionException() {
        return connectionException;
    }

    public void disconnect() {
        runCommunicationLoop = false;
    }

    /**
     * Is being called from within the firewall, as a package-decision is made.
     * For each package, there is one instance.
     */
    private class PackageActionCallbackHandler implements PackageActionCallback {
        private final String LOG_TAG = PackageActionCallbackHandler.class.getSimpleName();
        private final Packages.TransportLayerPackage tlPackage;

        private volatile boolean isAnswered = false;

        public boolean isAnswered() {
            return isAnswered;
        }

        public PackageActionCallbackHandler(Packages.TransportLayerPackage tlPackage) {
            this.tlPackage = tlPackage;
        }

        /**
         * Will start a thread which automatically answers the package with ACCEPT or BLOCK
         * after a certain amount of time.
         */
        public void startAutoAnswerCountdown(final boolean accept, final int timeoutInMilliseconds) {
            Thread answerThread = new Thread() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(timeoutInMilliseconds);
                    } catch(Exception e) {
                        Log.e(LOG_TAG, "Auto-Answer-Thread has been stopped due to exception: " + e.getMessage(), e);
                    }

                    if (isAnswered)
                        return;

                    Log.v(LOG_TAG, "Auto-Answer: " + accept);

                    if (accept)
                        acceptPendingPackage();
                    else
                        blockPendingPackage();
                }
            };

            answerThread.setDaemon(true);
            answerThread.start();
        }

        @Override
        public void acceptPendingPackage() {
            Log.v(LOG_TAG, "Accepting package: " + tlPackage);
            isAnswered = true;

            sendPackageQueryResponse(true);
        }

        @Override
        public void blockPendingPackage() {
            Log.v(LOG_TAG, "Dropping package: " + tlPackage);
            isAnswered = true;

            sendPackageQueryResponse(false);
        }
    }

//    /**
//     * Is being called from within the firewall, as a package-decision is made.
//     * For each package, there is one instance.
//     */
//    private class PackageActionCallbackHandler implements PackageActionCallback {
//        private final String LOG_TAG = PackageActionCallbackHandler.class.getSimpleName();
//        private final Packages.TransportLayerPackage tlPackage;
//
//        private volatile boolean isAnswered = false;
//
//        public boolean isAnswered() {
//            return isAnswered;
//        }
//
//        public PackageActionCallbackHandler(Packages.TransportLayerPackage tlPackage) {
//            this.tlPackage = tlPackage;
//        }
//
//        /**
//         * Will start a thread which automatically answers the package with ACCEPT or BLOCK
//         * after a certain amount of time.
//         */
//        public void startAutoAnswerCountdown(final boolean accept, final int timeoutInMilliseconds) {
//            Thread answerThread = new Thread() {
//                @Override
//                public void run() {
//                    try {
//                        Thread.sleep(timeoutInMilliseconds);
//                    } catch(Exception e) {
//                        Log.e(LOG_TAG, "Auto-Answer-Thread has been stopped due to exception: " + e.getMessage(), e);
//                    }
//
//                    if (isAnswered)
//                        return;
//
//                    Log.v(LOG_TAG, "Auto-Answer: " + accept);
//
//                    if (accept)
//                        acceptPackage(tlPackage);
//                    else
//                        blockPackage(tlPackage);
//                }
//            };
//
//            answerThread.setDaemon(true);
//            answerThread.start();
//        }
//
//        @Override
//        public void acceptPackage(Packages.TransportLayerPackage tlPackage) {
//            Log.v(LOG_TAG, "Accepting package: " + tlPackage);
//            isAnswered = true;
//
//            sendPackageQueryResponse(true);
//        }
//
//        @Override
//        public void blockPackage(Packages.TransportLayerPackage tlPackage) {
//            Log.v(LOG_TAG, "Dropping package: " + tlPackage);
//            isAnswered = true;
//
//            sendPackageQueryResponse(false);
//        }
//    }
}
