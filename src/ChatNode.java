import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ChatNode
{
    private static final int DNS_QUERY_TTL = 5;
    private static final int ANNOUNCE_TTL = 2;
    private static final long HEARTBEAT_INTERVAL_SECONDS = 3L;
    private static final long HEARTBEAT_TIMEOUT_MS = 10000L;
    private final String username;
    private final int port;
    private final DnsService dnsService;
    private final CryptoManager cryptoManager;
    private final ConnectionManager connectionManager;
    private final ConcurrentMap<String, CompletableFuture<PeerAddress>> pendingDnsQueries;
    private final ConcurrentMap<String, ConnectionHandler> queryRoutes;
    private final ConcurrentMap<String, Long> lastPongTimes;
    private final ConcurrentMap<String, BenchmarkState> benchmarks;
    private final ConcurrentMap<String, Integer> heartbeatLogCounts;
    private final ScheduledExecutorService heartbeatExecutor;
    private final Set<String> seenDnsQueries;
    private final Set<String> manuallyRegisteredUsers;
    private final boolean debugMode;
    private volatile boolean heartbeatLoggingEnabled;
    private volatile int heartbeatLogInterval;
    private volatile boolean running;
    private ServerSocket serverSocket;



    public ChatNode(String username, int port, String dnsCacheFile, boolean debugMode) throws GeneralSecurityException
    {
        this.username = username;
        this.port = port;
        this.dnsService = new DnsService(dnsCacheFile);
        this.cryptoManager = new CryptoManager();
        this.connectionManager = new ConnectionManager();
        this.pendingDnsQueries = new ConcurrentHashMap<String, CompletableFuture<PeerAddress>>();
        this.queryRoutes = new ConcurrentHashMap<String, ConnectionHandler>();
        this.lastPongTimes = new ConcurrentHashMap<String, Long>();
        this.benchmarks = new ConcurrentHashMap<String, BenchmarkState>();
        this.heartbeatLogCounts = new ConcurrentHashMap<String, Integer>();
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        this.seenDnsQueries = ConcurrentHashMap.newKeySet();
        this.manuallyRegisteredUsers = ConcurrentHashMap.newKeySet();
        this.debugMode = debugMode;
        this.heartbeatLoggingEnabled = false;
        this.heartbeatLogInterval = 5;

        if (debugMode)
        {
            preloadDebugDns();
        }
    }



    public void start() throws IOException
    {
        running = true;
        registerSelf();
        serverSocket = new ServerSocket(port);
        Thread listener = new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                listen();
            }
        });
        listener.setDaemon(true);
        listener.start();
        ConsolePrinter.info("Node " + username + " listening on port " + port);

        if (debugMode)
        {
            ConsolePrinter.info("Debug DNS preload enabled");
        }

        announceSelf();
        startAutoConnectThread();
        startHeartbeat();
    }



    public String getUsername()
    {
        return username;
    }



    public int getPort()
    {
        return port;
    }



    public CryptoManager getCryptoManager()
    {
        return cryptoManager;
    }



    public void register(String username, String host, int port)
    {
        manuallyRegisteredUsers.add(username);

        if (dnsService.merge(username, host, port))
        {
            broadcastUserAnnounce(username, new PeerAddress(host, port), ANNOUNCE_TTL, null);
        }
    }



    public PeerAddress resolve(String username)
    {
        return searchUser(username);
    }



    public PeerAddress searchUser(String targetUsername)
    {
        PeerAddress local = dnsService.resolve(targetUsername);

        if (local != null)
        {
            return local;
        }

        return queryNetworkForUser(targetUsername);
    }



    public List<String> searchAllUsers()
    {
        if (!connectionManager.isEmpty())
        {
            String queryId = UUID.randomUUID().toString();
            seenDnsQueries.add(queryId);
            broadcastDnsQuery(queryId, "*", DNS_QUERY_TTL, null);
            sleepQuietly(2000L);
        }

        return dnsService.describeRecords();
    }



    public List<String> dnsRecords()
    {
        List<String> lines = new ArrayList<String>();
        Map<String, PeerAddress> snapshot = dnsService.snapshot();

        for (Map.Entry<String, PeerAddress> entry : snapshot.entrySet())
        {
            String knownUser = entry.getKey();

            if (connectionManager.hasSession(knownUser) || manuallyRegisteredUsers.contains(knownUser))
            {
                lines.add("[DNS] " + knownUser + " -> " + entry.getValue().toSocketString());
            }
        }

        return lines;
    }



    public List<String> peerNames()
    {
        List<String> peers = new ArrayList<String>();

        for (String peer : connectionManager.usernames())
        {
            peers.add("[INFO] " + peer);
        }

        return peers;
    }



    public boolean connectToUser(String targetUsername)
    {
        return connectToUser(targetUsername, true);
    }



    public boolean connectToUser(String targetUsername, boolean showErrors)
    {
        if (connectionManager.hasSession(targetUsername))
        {
            return true;
        }

        PeerAddress address = resolve(targetUsername);

        if (address == null)
        {
            if (showErrors)
            {
                ConsolePrinter.warn("Peer " + targetUsername + " is offline or unreachable");
            }

            return false;
        }

        return connectWithRetry(address, targetUsername, showErrors);
    }



    public void sendMessage(String targetUsername, String body)
    {
        if (!connectToUser(targetUsername))
        {
            return;
        }

        ConnectionHandler handler = connectionManager.getSession(targetUsername);

        if (handler == null)
        {
            ConsolePrinter.warn("Peer " + targetUsername + " is offline or unreachable");
            return;
        }

        try
        {
            handler.sendChat(targetUsername, body);
        }
        catch (IOException exception)
        {
            ConsolePrinter.warn("Peer " + targetUsername + " is offline or unreachable");
            handler.close();

            if (connectToUser(targetUsername))
            {
                retrySend(targetUsername, body);
            }
        }
    }



    public void runBenchmark(String targetUsername, int count)
    {
        if (count <= 0)
        {
            ConsolePrinter.error("Benchmark count must be greater than zero");
            return;
        }

        if (!connectToUser(targetUsername))
        {
            return;
        }

        ConnectionHandler handler = connectionManager.getSession(targetUsername);

        if (handler == null)
        {
            ConsolePrinter.warn("Peer " + targetUsername + " is offline or unreachable");
            return;
        }

        String benchmarkId = UUID.randomUUID().toString();
        BenchmarkState state = new BenchmarkState(benchmarkId, count);
        benchmarks.put(benchmarkId, state);
        sendBenchmarkMessages(handler, targetUsername, state);
        waitForBenchmark(state);
        benchmarks.remove(state.getBenchmarkId());
    }



    public void setHeartbeatLogging(boolean enabled)
    {
        heartbeatLoggingEnabled = enabled;

        if (enabled)
        {
            ConsolePrinter.info("PING/PONG logging enabled; showing every " + heartbeatLogInterval + "th event per peer");
        }
        else
        {
            ConsolePrinter.info("PING/PONG logging disabled");
        }
    }



    public void setHeartbeatLogInterval(int interval)
    {
        if (interval < 5)
        {
            interval = 5;
        }
        else if (interval > 99)
        {
            interval = 99;
        }

        heartbeatLogInterval = interval;
        heartbeatLoggingEnabled = true;
        ConsolePrinter.info("PING/PONG logging enabled; showing every " + heartbeatLogInterval + "th event per peer");
    }



    public String heartbeatLogStatus()
    {
        String mode = heartbeatLoggingEnabled ? "enabled" : "disabled";
        return "[INFO] PING/PONG logging is " + mode + "; interval=" + heartbeatLogInterval + " (range 5-99)";
    }



    public boolean addPeer(String peerUsername, ConnectionHandler handler)
    {
        if (peerUsername == null)
        {
            return false;
        }

        if (connectionManager.addSession(peerUsername, handler))
        {
            lastPongTimes.put(peerUsername, Long.valueOf(System.currentTimeMillis()));
            ConsolePrinter.info("Connected to " + peerUsername);
            return true;
        }

        return false;
    }



    public void removePeer(String peerUsername, ConnectionHandler handler)
    {
        if (peerUsername != null && connectionManager.removeSession(peerUsername, handler))
        {
            lastPongTimes.remove(peerUsername);
            clearHeartbeatLogCounts(peerUsername);
            ConsolePrinter.info("Disconnected from " + peerUsername);
        }
    }



    public void shareDnsRecordsWith(ConnectionHandler handler)
    {
        try
        {
            handler.sendUserAnnounce(username, new PeerAddress("localhost", port), ANNOUNCE_TTL);
            handler.sendPeerList(serializeDnsTable());
        }
        catch (Exception exception)
        {
            return;
        }

        for (String knownUser : dnsService.usernames())
        {
            PeerAddress address = dnsService.resolve(knownUser);

            if (address != null)
            {
                try
                {
                    handler.sendDnsRegister(knownUser, address);
                }
                catch (Exception exception)
                {
                    return;
                }
            }
        }
    }



    public void handlePeerMessage(ConnectionHandler handler, Map<String, String> message)
    {
        String type = message.get("type");

        if ("chat".equals(type))
        {
            handleChat(handler, message);
        }
        else if ("PING".equals(type))
        {
            handlePing(handler, message);
        }
        else if ("PONG".equals(type))
        {
            handlePong(handler);
        }
        else if ("MESSAGE_ACK".equals(type))
        {
            handleMessageAck(message);
        }
        else if ("BENCHMARK".equals(type))
        {
            handleBenchmark(handler, message);
        }
        else if ("BENCHMARK_ACK".equals(type))
        {
            handleBenchmarkAck(message);
        }
        else if ("dns_register".equals(type))
        {
            handleDnsRegister(handler, message);
        }
        else if ("PEER_LIST".equals(type))
        {
            handlePeerList(handler, message);
        }
        else if ("USER_ANNOUNCE".equals(type) || "DNS_UPDATE".equals(type))
        {
            handleUserAnnounce(handler, message);
        }
        else if ("DNS_QUERY".equals(type) || "dns_query".equals(type))
        {
            handleDnsQuery(handler, message);
        }
        else if ("DNS_RESPONSE".equals(type) || "dns_response".equals(type))
        {
            handleDnsResponse(handler, message);
        }
    }



    public void stop()
    {
        running = false;

        try
        {
            if (serverSocket != null)
            {
                serverSocket.close();
            }
        }
        catch (IOException exception)
        {
            ConsolePrinter.warn("Listener did not stop cleanly");
        }

        for (ConnectionHandler handler : connectionManager.handlers())
        {
            handler.close();
        }

        heartbeatExecutor.shutdownNow();
    }



    private void retrySend(String targetUsername, String body)
    {
        try
        {
            connectionManager.getSession(targetUsername).sendChat(targetUsername, body);
        }
        catch (Exception retryException)
        {
            ConsolePrinter.warn("Peer " + targetUsername + " is offline or unreachable");
        }
    }



    private void startHeartbeat()
    {
        heartbeatExecutor.scheduleAtFixedRate(new Runnable()
        {
            @Override
            public void run()
            {
                sendHeartbeats();
            }
        }, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }



    private void sendHeartbeats()
    {
        long now = System.currentTimeMillis();

        for (ConnectionHandler handler : connectionManager.handlers())
        {
            String peer = handler.getRemoteUsername();

            if (peer == null)
            {
                continue;
            }

            Long lastPong = lastPongTimes.get(peer);

            if (lastPong != null && now - lastPong.longValue() > HEARTBEAT_TIMEOUT_MS)
            {
                ConsolePrinter.warn("Peer " + peer + " is offline or unreachable");
                handler.close();
                continue;
            }

            try
            {
                handler.sendPing(UUID.randomUUID().toString());
                logPing("sent", peer, "Sent to " + peer);
            }
            catch (IOException exception)
            {
                handler.close();
            }
        }
    }



    private void handleChat(ConnectionHandler handler, Map<String, String> message)
    {
        long latency = calculateLatency(message.get("sentAt"));
        ConsolePrinter.incoming("[MSG][" + message.get("from") + "]: " + message.get("body") + " (" + latency + " ms)");

        try
        {
            handler.sendMessageAck(message.get("messageId"), latency);
        }
        catch (IOException exception)
        {
            return;
        }
    }



    private void handlePing(ConnectionHandler handler, Map<String, String> message)
    {
        logPing("received", handler.getRemoteUsername(), "Received from " + handler.getRemoteUsername());

        try
        {
            handler.sendPong(message.get("pingId"));
            logPong("sent", handler.getRemoteUsername(), "Sent to " + handler.getRemoteUsername());
        }
        catch (IOException exception)
        {
            handler.close();
        }
    }



    private void handlePong(ConnectionHandler handler)
    {
        if (handler.getRemoteUsername() != null)
        {
            lastPongTimes.put(handler.getRemoteUsername(), Long.valueOf(System.currentTimeMillis()));
            logPong("received", handler.getRemoteUsername(), "Received from " + handler.getRemoteUsername());
        }
    }



    private void handleMessageAck(Map<String, String> message)
    {
        ConsolePrinter.info("Delivered to " + message.get("from") + " in " + message.get("latencyMs") + " ms");
    }



    private void logPing(String direction, String peer, String message)
    {
        if (shouldLogHeartbeat("PING", direction, peer))
        {
            ConsolePrinter.ping(message);
        }
    }



    private void logPong(String direction, String peer, String message)
    {
        if (shouldLogHeartbeat("PONG", direction, peer))
        {
            ConsolePrinter.pong(message);
        }
    }



    private boolean shouldLogHeartbeat(String type, String direction, String peer)
    {
        if (peer == null)
        {
            return false;
        }

        String key = type + ":" + direction + ":" + peer;
        Integer current = heartbeatLogCounts.get(key);
        int next = current == null ? 1 : current.intValue() + 1;
        heartbeatLogCounts.put(key, Integer.valueOf(next));

        if (next == 1)
        {
            return true;
        }

        return heartbeatLoggingEnabled && next % heartbeatLogInterval == 0;
    }



    private void clearHeartbeatLogCounts(String peer)
    {
        heartbeatLogCounts.remove("PING:sent:" + peer);
        heartbeatLogCounts.remove("PING:received:" + peer);
        heartbeatLogCounts.remove("PONG:sent:" + peer);
        heartbeatLogCounts.remove("PONG:received:" + peer);
    }



    private void handleBenchmark(ConnectionHandler handler, Map<String, String> message)
    {
        long latency = calculateLatency(message.get("sentAt"));

        try
        {
            handler.sendBenchmarkAck(message.get("benchmarkId"), parseInteger(message.get("sequence")), latency);
        }
        catch (IOException exception)
        {
            handler.close();
        }
    }



    private void handleBenchmarkAck(Map<String, String> message)
    {
        BenchmarkState state = benchmarks.get(message.get("benchmarkId"));

        if (state != null)
        {
            state.recordLatency(parseLong(message.get("latencyMs")));
        }
    }



    private void sendBenchmarkMessages(ConnectionHandler handler, String targetUsername, BenchmarkState state)
    {
        ConsolePrinter.info("Benchmark started: " + state.getExpectedMessages() + " messages to " + targetUsername);

        for (int index = 1; index <= state.getExpectedMessages(); index++)
        {
            try
            {
                handler.sendBenchmark(targetUsername, state.getBenchmarkId(), index);
            }
            catch (IOException exception)
            {
                ConsolePrinter.error("Benchmark send failed");
                return;
            }
        }
    }



    private void waitForBenchmark(BenchmarkState state)
    {
        long deadline = System.currentTimeMillis() + 15000L;

        while (!state.isComplete() && System.currentTimeMillis() < deadline)
        {
            sleepQuietly(50L);
        }

        ConsolePrinter.info("Benchmark complete: received " + state.getReceivedAcks() + "/" + state.getExpectedMessages() + " ACKs");
        ConsolePrinter.info("Benchmark total time: " + state.getElapsedTime() + " ms");
        ConsolePrinter.info("Benchmark average latency: " + state.getAverageLatency() + " ms");
    }



    private void registerSelf()
    {
        dnsService.merge(username, "localhost", port);
    }



    private void announceSelf()
    {
        broadcastUserAnnounce(username, new PeerAddress("localhost", port), ANNOUNCE_TTL, null);
    }



    private void preloadDebugDns()
    {
        dnsService.merge("userA", "localhost", 5000);
        dnsService.merge("userB", "localhost", 5001);
    }



    private void listen()
    {
        while (running)
        {
            try
            {
                Socket socket = serverSocket.accept();
                ConnectionHandler handler = new ConnectionHandler(this, socket, false);
                new Thread(handler).start();
            }
            catch (IOException exception)
            {
                if (running)
                {
                    continue;
                }
            }
        }
    }



    private void startAutoConnectThread()
    {
        Thread autoConnectThread = new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                sleepQuietly(500L);
                connectToKnownPeersOnStartup();
            }
        });
        autoConnectThread.setDaemon(true);
        autoConnectThread.start();
    }



    private void connectToKnownPeersOnStartup()
    {
        for (String knownUser : dnsService.usernames())
        {
            if (username.equals(knownUser) || connectionManager.hasSession(knownUser))
            {
                continue;
            }

            PeerAddress address = dnsService.resolve(knownUser);

            if (address != null)
            {
                connectToKnownPeer(knownUser, address);
            }
        }
    }



    private void connectToKnownPeer(String knownUser, PeerAddress address)
    {
        try
        {
            Socket socket = new Socket(address.getHost(), address.getPort());
            ConnectionHandler handler = new ConnectionHandler(this, socket, true);
            Thread thread = new Thread(handler);
            thread.start();
            waitForPeer(knownUser);

            if (connectionManager.hasSession(knownUser))
            {
                ConsolePrinter.info("Connected to " + knownUser);
            }
        }
        catch (IOException exception)
        {
            return;
        }
    }



    private boolean connectWithRetry(PeerAddress address, String targetUsername, boolean showErrors)
    {
        boolean errorShown = false;

        for (int attempt = 1; attempt <= 2; attempt++)
        {
            try
            {
                Socket socket = new Socket(address.getHost(), address.getPort());
                ConnectionHandler handler = new ConnectionHandler(this, socket, true);
                Thread thread = new Thread(handler);
                thread.start();
                waitForPeer(targetUsername);

                if (connectionManager.hasSession(targetUsername))
                {
                    return true;
                }
            }
            catch (IOException exception)
            {
                if (attempt == 2)
                {
                    if (showErrors)
                    {
                        ConsolePrinter.warn("Peer " + targetUsername + " is offline or unreachable");
                        errorShown = true;
                    }
                }
            }
        }

        if (showErrors && !errorShown)
        {
            ConsolePrinter.warn("Peer " + targetUsername + " is offline or unreachable");
        }

        return false;
    }



    private void waitForPeer(String targetUsername)
    {
        long deadline = System.currentTimeMillis() + 3000L;

        while (System.currentTimeMillis() < deadline)
        {
            if (connectionManager.hasSession(targetUsername))
            {
                return;
            }

            sleepQuietly(50L);
        }
    }



    private PeerAddress queryNetworkForUser(String targetUsername)
    {
        if (connectionManager.isEmpty())
        {
            return null;
        }

        String queryId = UUID.randomUUID().toString();
        CompletableFuture<PeerAddress> future = new CompletableFuture<PeerAddress>();
        pendingDnsQueries.put(queryId, future);
        seenDnsQueries.add(queryId);
        broadcastDnsQuery(queryId, targetUsername, DNS_QUERY_TTL, null);

        try
        {
            PeerAddress address = future.get(3, java.util.concurrent.TimeUnit.SECONDS);

            if (address != null)
            {
                dnsService.merge(targetUsername, address.getHost(), address.getPort());
            }

            return address;
        }
        catch (Exception exception)
        {
            return null;
        }
        finally
        {
            pendingDnsQueries.remove(queryId);
        }
    }



    private void broadcastDnsQuery(String queryId, String targetUsername, int ttl, ConnectionHandler except)
    {
        for (ConnectionHandler handler : connectionManager.handlers())
        {
            if (handler == except)
            {
                continue;
            }

            try
            {
                handler.sendDnsQuery(queryId, targetUsername, ttl);
            }
            catch (Exception exception)
            {
                continue;
            }
        }
    }



    private void broadcastDnsRecord(String username, PeerAddress address, ConnectionHandler except)
    {
        for (ConnectionHandler handler : connectionManager.handlers())
        {
            if (handler == except)
            {
                continue;
            }

            try
            {
                handler.sendDnsRegister(username, address);
            }
            catch (Exception exception)
            {
                continue;
            }
        }
    }



    private void broadcastUserAnnounce(String username, PeerAddress address, int ttl, ConnectionHandler except)
    {
        if (ttl < 0)
        {
            return;
        }

        for (ConnectionHandler handler : connectionManager.handlers())
        {
            if (handler == except)
            {
                continue;
            }

            try
            {
                handler.sendUserAnnounce(username, address, ttl);
            }
            catch (Exception exception)
            {
                continue;
            }
        }
    }



    private void handleDnsRegister(ConnectionHandler sender, Map<String, String> message)
    {
        try
        {
            String knownUser = message.get("username");
            String host = message.get("host");
            int knownPort = Integer.parseInt(message.get("port"));
            boolean changed = dnsService.merge(knownUser, host, knownPort);

            if (changed)
            {
                broadcastUserAnnounce(knownUser, new PeerAddress(host, knownPort), ANNOUNCE_TTL, sender);
            }
        }
        catch (NumberFormatException exception)
        {
            ConsolePrinter.warn("Ignored malformed DNS registration");
        }
    }



    private void handlePeerList(ConnectionHandler sender, Map<String, String> message)
    {
        mergeSerializedDnsTable(message.get("records"), true);
    }



    private void handleUserAnnounce(ConnectionHandler sender, Map<String, String> message)
    {
        try
        {
            String announcedUser = message.get("username");
            String host = message.get("host");
            int announcedPort = Integer.parseInt(message.get("port"));
            int ttl = parseTtl(message.get("ttl"));
            boolean changed = dnsService.merge(announcedUser, host, announcedPort);

            if (changed)
            {
                ConsolePrinter.dns("Learned " + announcedUser + " -> " + host + ":" + announcedPort);
            }

            if (changed && ttl > 0)
            {
                broadcastUserAnnounce(announcedUser, new PeerAddress(host, announcedPort), ttl - 1, sender);
            }
        }
        catch (NumberFormatException exception)
        {
            ConsolePrinter.warn("Ignored malformed user announcement");
        }
    }



    private void handleDnsQuery(ConnectionHandler sender, Map<String, String> message)
    {
        String queryId = message.get("queryId");

        if (queryId == null || !seenDnsQueries.add(queryId))
        {
            return;
        }

        queryRoutes.put(queryId, sender);
        String requestedUser = message.get("username");
        int ttl = parseTtl(message.get("ttl"));

        try
        {
            if ("*".equals(requestedUser))
            {
                sender.sendDnsTableResponse(queryId, serializeDnsTable());
            }
            else
            {
                PeerAddress address = dnsService.resolve(requestedUser);

                if (address != null)
                {
                    sender.sendDnsResponse(queryId, requestedUser, address);
                }
            }

            if (ttl > 0)
            {
                broadcastDnsQuery(queryId, requestedUser, ttl - 1, sender);
            }
        }
        catch (Exception exception)
        {
            return;
        }
    }



    private void handleDnsResponse(ConnectionHandler sender, Map<String, String> message)
    {
        String queryId = message.get("queryId");
        PeerAddress userAddress = mergeDnsResponse(message);
        CompletableFuture<PeerAddress> future = pendingDnsQueries.get(queryId);

        if (future != null && userAddress != null)
        {
            future.complete(userAddress);
            return;
        }

        ConnectionHandler route = queryRoutes.get(queryId);

        if (route != null && route != sender)
        {
            forwardDnsResponse(route, message, userAddress);
        }
    }



    private PeerAddress mergeDnsResponse(Map<String, String> message)
    {
        if ("table".equals(message.get("responseKind")))
        {
            mergeSerializedDnsTable(message.get("records"), false);
            return null;
        }

        if (!Boolean.parseBoolean(message.get("found")))
        {
            return null;
        }

        try
        {
            String foundUser = message.get("username");
            PeerAddress address = new PeerAddress(message.get("host"), Integer.parseInt(message.get("port")));
            dnsService.merge(foundUser, address.getHost(), address.getPort());
            return address;
        }
        catch (NumberFormatException exception)
        {
            ConsolePrinter.warn("Ignored malformed DNS response");
            return null;
        }
    }



    private void forwardDnsResponse(ConnectionHandler route, Map<String, String> message, PeerAddress userAddress)
    {
        try
        {
            if ("table".equals(message.get("responseKind")))
            {
                route.sendDnsTableResponse(message.get("queryId"), message.get("records"));
            }
            else
            {
                route.sendDnsResponse(message.get("queryId"), message.get("username"), userAddress);
            }
        }
        catch (Exception exception)
        {
            return;
        }
    }



    private String serializeDnsTable()
    {
        StringBuilder builder = new StringBuilder();

        for (Map.Entry<String, PeerAddress> entry : dnsService.snapshot().entrySet())
        {
            builder.append(entry.getKey());
            builder.append(",");
            builder.append(entry.getValue().getHost());
            builder.append(",");
            builder.append(entry.getValue().getPort());
            builder.append(";");
        }

        return builder.toString();
    }



    private void mergeSerializedDnsTable(String records, boolean avoidDuplicateAddress)
    {
        if (records == null)
        {
            return;
        }

        String[] lines = records.split(";");

        for (String line : lines)
        {
            String[] parts = line.split(",", 3);

            if (parts.length == 3)
            {
                try
                {
                    int learnedPort = Integer.parseInt(parts[2]);
                    boolean changed;

                    if (avoidDuplicateAddress)
                    {
                        changed = dnsService.mergeIfAddressUnknown(parts[0], parts[1], learnedPort);
                    }
                    else
                    {
                        changed = dnsService.merge(parts[0], parts[1], learnedPort);
                    }

                    if (changed)
                    {
                        ConsolePrinter.dns("Learned " + parts[0] + " -> " + parts[1] + ":" + learnedPort);
                    }
                }
                catch (NumberFormatException exception)
                {
                    ConsolePrinter.warn("Ignored malformed DNS table row");
                }
            }
        }
    }



    private int parseTtl(String value)
    {
        try
        {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException exception)
        {
            return 0;
        }
    }



    private int parseInteger(String value)
    {
        try
        {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException exception)
        {
            return 0;
        }
    }



    private long parseLong(String value)
    {
        try
        {
            return Long.parseLong(value);
        }
        catch (NumberFormatException exception)
        {
            return 0L;
        }
    }



    private long calculateLatency(String sentAt)
    {
        long start = parseLong(sentAt);

        if (start <= 0L)
        {
            return 0L;
        }

        return System.currentTimeMillis() - start;
    }



    private void sleepQuietly(long milliseconds)
    {
        try
        {
            Thread.sleep(milliseconds);
        }
        catch (InterruptedException exception)
        {
            Thread.currentThread().interrupt();
        }
    }
}
