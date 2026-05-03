import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import javax.crypto.SecretKey;

public class ConnectionHandler implements Runnable
{
    private final ChatNode node;
    private final Socket socket;
    private final boolean outgoing;
    private final CryptoManager cryptoManager;
    private final AesCipher aesCipher;
    private final BlockingQueue<Map<String, String>> outgoingMessages;
    private DataInputStream input;
    private DataOutputStream output;
    private SecretKey aesKey;
    private String remoteUsername;
    private volatile boolean running;
    private Thread senderThread;



    public ConnectionHandler(ChatNode node, Socket socket, boolean outgoing)
    {
        this.node = node;
        this.socket = socket;
        this.outgoing = outgoing;
        this.cryptoManager = node.getCryptoManager();
        this.aesCipher = new AesCipher();
        this.outgoingMessages = new LinkedBlockingQueue<Map<String, String>>();
        this.running = true;
    }



    @Override
    public void run()
    {
        try
        {
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
            performHandshake();
            ConsolePrinter.secure("AES session key established with " + remoteUsername);
            startSenderThread();

            if (!node.addPeer(remoteUsername, this))
            {
                ConsolePrinter.info("Reusing existing secure session with " + remoteUsername);
                return;
            }

            node.shareDnsRecordsWith(this);
            listenForMessages();
        }
        catch (IOException exception)
        {
            return;
        }
        catch (GeneralSecurityException exception)
        {
            return;
        }
        finally
        {
            close();
        }
    }



    public String getRemoteUsername()
    {
        return remoteUsername;
    }



    public boolean isRunning()
    {
        return running && !socket.isClosed();
    }



    public String sendChat(String to, String body) throws IOException
    {
        String messageId = java.util.UUID.randomUUID().toString();
        Map<String, String> message = new LinkedHashMap<String, String>();
        message.put("type", "chat");
        message.put("messageId", messageId);
        message.put("from", node.getUsername());
        message.put("to", to);
        message.put("body", body);
        message.put("timestamp", Instant.now().toString());
        message.put("sentAt", Long.toString(System.currentTimeMillis()));
        queueMessage(message);
        return messageId;
    }



    public void sendPing(String pingId) throws IOException
    {
        Map<String, String> message = new LinkedHashMap<String, String>();
        message.put("type", "PING");
        message.put("pingId", pingId);
        message.put("from", node.getUsername());
        message.put("sentAt", Long.toString(System.currentTimeMillis()));
        queueMessage(message);
    }



    public void sendPong(String pingId) throws IOException
    {
        Map<String, String> message = new LinkedHashMap<String, String>();
        message.put("type", "PONG");
        message.put("pingId", pingId);
        message.put("from", node.getUsername());
        message.put("sentAt", Long.toString(System.currentTimeMillis()));
        queueMessage(message);
    }



    public void sendMessageAck(String messageId, long latency) throws IOException
    {
        Map<String, String> message = new LinkedHashMap<String, String>();
        message.put("type", "MESSAGE_ACK");
        message.put("messageId", messageId);
        message.put("from", node.getUsername());
        message.put("latencyMs", Long.toString(latency));
        queueMessage(message);
    }



    public void sendBenchmark(String to, String benchmarkId, int sequence) throws IOException
    {
        Map<String, String> message = new LinkedHashMap<String, String>();
        message.put("type", "BENCHMARK");
        message.put("messageId", java.util.UUID.randomUUID().toString());
        message.put("benchmarkId", benchmarkId);
        message.put("sequence", Integer.toString(sequence));
        message.put("from", node.getUsername());
        message.put("to", to);
        message.put("sentAt", Long.toString(System.currentTimeMillis()));
        queueMessage(message);
    }



    public void sendBenchmarkAck(String benchmarkId, int sequence, long latency) throws IOException
    {
        Map<String, String> message = new LinkedHashMap<String, String>();
        message.put("type", "BENCHMARK_ACK");
        message.put("benchmarkId", benchmarkId);
        message.put("sequence", Integer.toString(sequence));
        message.put("from", node.getUsername());
        message.put("latencyMs", Long.toString(latency));
        queueMessage(message);
    }



    public void sendDnsRegister(String username, PeerAddress address) throws IOException
    {
        Map<String, String> message = new LinkedHashMap<String, String>();
        message.put("type", "dns_register");
        message.put("username", username);
        message.put("host", address.getHost());
        message.put("port", Integer.toString(address.getPort()));
        message.put("timestamp", Instant.now().toString());
        queueMessage(message);
    }



    public void sendPeerList(String records) throws IOException
    {
        Map<String, String> message = new LinkedHashMap<String, String>();
        message.put("type", "PEER_LIST");
        message.put("records", records);
        message.put("timestamp", Instant.now().toString());
        queueMessage(message);
    }



    public void sendUserAnnounce(String username, PeerAddress address, int ttl) throws IOException
    {
        Map<String, String> message = new LinkedHashMap<String, String>();
        message.put("type", "USER_ANNOUNCE");
        message.put("username", username);
        message.put("host", address.getHost());
        message.put("port", Integer.toString(address.getPort()));
        message.put("ttl", Integer.toString(ttl));
        message.put("timestamp", Instant.now().toString());
        queueMessage(message);
    }



    public void sendDnsQuery(String queryId, String username, int ttl) throws IOException
    {
        Map<String, String> message = new LinkedHashMap<String, String>();
        message.put("type", "DNS_QUERY");
        message.put("queryId", queryId);
        message.put("username", username);
        message.put("ttl", Integer.toString(ttl));
        message.put("timestamp", Instant.now().toString());
        queueMessage(message);
    }



    public void sendDnsResponse(String queryId, String username, PeerAddress address) throws IOException
    {
        Map<String, String> message = new LinkedHashMap<String, String>();
        message.put("type", "DNS_RESPONSE");
        message.put("responseKind", "user");
        message.put("queryId", queryId);
        message.put("username", username);
        message.put("found", Boolean.toString(address != null));

        if (address != null)
        {
            message.put("host", address.getHost());
            message.put("port", Integer.toString(address.getPort()));
        }

        message.put("timestamp", Instant.now().toString());
        queueMessage(message);
    }



    public void sendDnsTableResponse(String queryId, String records) throws IOException
    {
        Map<String, String> message = new LinkedHashMap<String, String>();
        message.put("type", "DNS_RESPONSE");
        message.put("responseKind", "table");
        message.put("queryId", queryId);
        message.put("records", records);
        message.put("timestamp", Instant.now().toString());
        queueMessage(message);
    }



    public void close()
    {
        running = false;
        node.removePeer(remoteUsername, this);

        if (senderThread != null)
        {
            senderThread.interrupt();
        }

        try
        {
            socket.close();
        }
        catch (IOException exception)
        {
            // Closing an already closed socket is harmless for this CLI app.
        }
    }



    private void startSenderThread()
    {
        senderThread = new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                sendLoop();
            }
        });
        senderThread.setDaemon(true);
        senderThread.start();
    }



    private void performHandshake() throws IOException, GeneralSecurityException
    {
        if (outgoing)
        {
            sendPlainHello();
            Map<String, String> hello = readPlainMessage();
            acceptHello(hello);
            SecretKey sessionKey = cryptoManager.createAesKey();
            PublicKey remotePublicKey = cryptoManager.decodePublicKey(hello.get("publicKey"));
            sendPlainKey(cryptoManager.encryptAesKey(sessionKey, remotePublicKey));
            aesKey = sessionKey;
        }
        else
        {
            Map<String, String> hello = readPlainMessage();
            acceptHello(hello);
            sendPlainHello();
            Map<String, String> keyMessage = readPlainMessage();
            aesKey = cryptoManager.decryptAesKey(keyMessage.get("encryptedKey"));
        }
    }



    private void sendPlainHello() throws IOException
    {
        Map<String, String> message = new LinkedHashMap<String, String>();
        message.put("type", "hello");
        message.put("username", node.getUsername());
        message.put("publicKey", cryptoManager.getEncodedPublicKey());
        writeRaw(JsonUtil.stringify(message));
    }



    private void acceptHello(Map<String, String> hello) throws IOException
    {
        if (!"hello".equals(hello.get("type")))
        {
            throw new IOException("Expected hello message");
        }

        remoteUsername = hello.get("username");
    }



    private void sendPlainKey(String encryptedKey) throws IOException
    {
        Map<String, String> message = new LinkedHashMap<String, String>();
        message.put("type", "key");
        message.put("encryptedKey", encryptedKey);
        writeRaw(JsonUtil.stringify(message));
    }



    private Map<String, String> readPlainMessage() throws IOException
    {
        return JsonUtil.parse(input.readUTF());
    }



    private void listenForMessages() throws IOException, GeneralSecurityException
    {
        while (running)
        {
            String raw = input.readUTF();
            Map<String, String> wrapper = JsonUtil.parse(raw);

            if (!"encrypted".equals(wrapper.get("type")))
            {
                continue;
            }

            String plaintext = aesCipher.decrypt(wrapper.get("payload"), aesKey);
            Map<String, String> message = JsonUtil.parse(plaintext);
            node.handlePeerMessage(this, message);
        }
    }



    private void queueMessage(Map<String, String> message) throws IOException
    {
        if (!isRunning())
        {
            throw new IOException("Session is not connected");
        }

        outgoingMessages.offer(message);
    }



    private void sendLoop()
    {
        while (running)
        {
            try
            {
                Map<String, String> message = outgoingMessages.take();
                sendEncrypted(message);
            }
            catch (InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                return;
            }
            catch (Exception exception)
            {
                close();
                return;
            }
        }
    }



    private void sendEncrypted(Map<String, String> message) throws IOException, GeneralSecurityException
    {
        String payload = aesCipher.encrypt(JsonUtil.stringify(message), aesKey);
        Map<String, String> wrapper = new LinkedHashMap<String, String>();
        wrapper.put("type", "encrypted");
        wrapper.put("payload", payload);
        writeRaw(JsonUtil.stringify(wrapper));
    }



    private void writeRaw(String raw) throws IOException
    {
        output.writeUTF(raw);
        output.flush();
    }
}
